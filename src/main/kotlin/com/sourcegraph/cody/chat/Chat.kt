package com.sourcegraph.cody.chat

import com.intellij.openapi.project.Project
import com.sourcegraph.cody.UpdatableChat
import com.sourcegraph.cody.agent.CodyAgentService
import com.sourcegraph.cody.agent.ExtensionMessage
import com.sourcegraph.cody.agent.WebviewMessage
import com.sourcegraph.cody.agent.protocol.*
import com.sourcegraph.cody.agent.protocol.ErrorCodeUtils.toErrorCode
import com.sourcegraph.cody.agent.protocol.RateLimitError.Companion.toRateLimitError
import com.sourcegraph.cody.config.RateLimitStateManager
import com.sourcegraph.cody.vscode.CancellationToken
import com.sourcegraph.common.CodyBundle
import com.sourcegraph.common.CodyBundle.fmt
import com.sourcegraph.common.UpgradeToCodyProNotification.Companion.isCodyProJetbrains
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.slf4j.LoggerFactory

class Chat {
  val logger = LoggerFactory.getLogger(Chat::class.java)

  @Throws(ExecutionException::class, InterruptedException::class)
  fun sendMessageViaAgent(
      project: Project,
      humanMessage: ChatMessage,
      recipeId: String,
      chat: UpdatableChat,
      token: CancellationToken
  ) {
    CodyAgentService.applyAgentOnBackgroundThread(project) { agent ->
      val isFirstMessage = AtomicBoolean(false)
      agent.client.onFinishedProcessing = Runnable { chat.finishMessageProcessing() }
      agent.client.onChatUpdateMessageInProgress = Consumer { agentChatMessage ->
        val agentChatMessageText = agentChatMessage.text ?: return@Consumer
        val chatMessage =
            ChatMessage(Speaker.ASSISTANT, agentChatMessageText, agentChatMessage.displayText)
        if (isFirstMessage.compareAndSet(false, true)) {
          val contextMessages =
              agentChatMessage.contextFiles?.map { contextFile: ContextFile ->
                ContextMessage(Speaker.ASSISTANT, agentChatMessageText, contextFile)
              }
                  ?: emptyList()
          chat.displayUsedContext(contextMessages)
          chat.addMessageToChat(chatMessage)
        } else {
          chat.updateLastMessage(chatMessage)
        }
      }
      if (recipeId == "chat-question") {
        try {
          val reply =
              agent.server.chatSubmitMessage(
                  ChatSubmitMessageParams(
                      chat.id!!,
                      WebviewMessage(
                          command = "submit",
                          text = humanMessage.actualMessage(),
                          submitType = "user",
                          addEnhancedContext = true,
                          // TODO(#242): allow to manually add files to the context via `@`
                          contextFiles = listOf())))
          token.onCancellationRequested { reply.cancel(true) }
          reply.handle { lastReply, error ->
            if (error != null) {
              logger.warn("Error while sending the message", error)

              if (error.message?.startsWith("No panel with ID") == true) {
                chat.loadNewChatId {
                  sendMessageViaAgent(project, humanMessage, recipeId, chat, token)
                }
              } else {
                handleError(project, error, chat)
              }
            } else {
              val err = lastReply.messages?.lastOrNull()?.error
              if (lastReply.type == ExtensionMessage.Type.TRANSCRIPT && err != null) {
                val rateLimitError = err.toRateLimitError()
                if (rateLimitError != null) {
                  handleRateLimitError(project, chat, rateLimitError)
                }
              } else {
                RateLimitStateManager.invalidateForChat(project)
              }
            }
          }
        } catch (ignored: Exception) {
          // Ignore bugs in the agent when executing recipes
          logger.warn("Ignored error executing recipe: $ignored")
        }
      } else {
        // TODO: migrate recipes to new webview-based API and then delete this else condition.
        try {
          val recipesExecuteFuture =
              agent.server.recipesExecute(
                  ExecuteRecipeParams(recipeId, humanMessage.actualMessage()))
          token.onCancellationRequested { recipesExecuteFuture.cancel(true) }
          recipesExecuteFuture.handle { _, error ->
            if (error != null) {
              handleError(project, error, chat)
              null
            } else {
              RateLimitStateManager.invalidateForChat(project)
            }
          }
        } catch (ignored: Exception) {
          // Ignore bugs in the agent when executing recipes
          logger.warn("Ignored error executing recipe: $ignored")
        }
      }
    }
  }

  private fun handleRateLimitError(
      project: Project,
      chat: UpdatableChat,
      rateLimitError: RateLimitError
  ) {
    RateLimitStateManager.reportForChat(project, rateLimitError)

    isCodyProJetbrains(project).thenApply { isCodyPro ->
      val text =
          when {
            rateLimitError.upgradeIsAvailable && isCodyPro ->
                CodyBundle.getString("chat.rate-limit-error.upgrade")
                    .fmt(rateLimitError.limit?.let { " $it" } ?: "")
            else -> CodyBundle.getString("chat.rate-limit-error.explain")
          }

      val chatMessage = ChatMessage(Speaker.ASSISTANT, text, null)
      chat.addMessageToChat(chatMessage)
      chat.finishMessageProcessing()
    }
  }

  private fun handleError(project: Project, throwable: Throwable, chat: UpdatableChat) {
    if (throwable is ResponseErrorException) {
      val errorCode = throwable.toErrorCode()
      if (errorCode == ErrorCode.RateLimitError) {
        handleRateLimitError(project, chat, throwable.toRateLimitError())
        return
      }
    }
    RateLimitStateManager.invalidateForChat(project)

    // todo: error handling for other error codes and throwables
    chat.finishMessageProcessing()
  }
}
