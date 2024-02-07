package com.sourcegraph.cody.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.util.IconUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.sourcegraph.cody.PromptPanel
import com.sourcegraph.cody.agent.protocol.ChatMessage
import com.sourcegraph.cody.chat.ChatSession
import com.sourcegraph.cody.context.ui.EnhancedContextPanel
import com.sourcegraph.cody.ui.ChatScrollPane
import com.sourcegraph.cody.vscode.CancellationToken
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

class ChatPanel(project: Project, chatSession: ChatSession) :
    JPanel(VerticalFlowLayout(VerticalFlowLayout.CENTER, 0, 0, true, false)) {

  val promptPanel: PromptPanel = PromptPanel(chatSession)
  private val messagesPanel = MessagesPanel(project, chatSession)
  private val chatPanel = ChatScrollPane(messagesPanel)
  private val contextView: EnhancedContextPanel = EnhancedContextPanel(project)

  private val stopGeneratingButton =
      object : JButton("Stop generating", IconUtil.desaturate(AllIcons.Actions.Suspend)) {
        init {
          isVisible = false
          layout = FlowLayout(FlowLayout.CENTER)
          minimumSize = Dimension(Short.MAX_VALUE.toInt(), 0)
          isOpaque = false
        }
      }

  init {
    layout = BorderLayout()
    border = BorderFactory.createEmptyBorder(0, 0, 0, 10)
    add(chatPanel, BorderLayout.CENTER)

    val lowerPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.BOTTOM, 10, 10, true, false))
    lowerPanel.add(stopGeneratingButton)
    lowerPanel.add(promptPanel)
    lowerPanel.add(contextView)
    add(lowerPanel, BorderLayout.SOUTH)
  }

  fun isEnhancedContextEnabled(): Boolean = contextView.isEnhancedContextEnabled.get()

  @RequiresEdt
  fun addOrUpdateMessage(message: ChatMessage, shouldAddBlinkingCursor: Boolean = true) {
    messagesPanel.addOrUpdateMessage(message, shouldAddBlinkingCursor)
  }

  @RequiresEdt
  fun registerCancellationToken(cancellationToken: CancellationToken) {
    messagesPanel.registerCancellationToken(cancellationToken)
    promptPanel.registerCancellationToken(cancellationToken)

    cancellationToken.onFinished { stopGeneratingButton.isVisible = false }

    stopGeneratingButton.isVisible = true
    for (listener in stopGeneratingButton.actionListeners) {
      stopGeneratingButton.removeActionListener(listener)
    }
    stopGeneratingButton.addActionListener { cancellationToken.abort() }
  }
}