package app.termora.tunnel

import app.termora.ApplicationScope
import app.termora.I18n
import app.termora.Icons
import app.termora.actions.AnAction
import app.termora.actions.AnActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

class TunnelAction private constructor() : AnAction(
    I18n.getString("termora.tunnel.manager"),
    Icons.forwardPorts
) {
    companion object {
        const val TUNNEL_MANAGER = "TunnelManagerAction"

        fun getInstance(): TunnelAction {
            return ApplicationScope.forApplicationScope().getOrCreate(TunnelAction::class) { TunnelAction() }
        }
    }

    private var isShowing = false

    override fun actionPerformed(evt: AnActionEvent) {
        if (isShowing) return
        isShowing = true

        val owner = evt.window
        val dialog = TunnelDialog(owner)
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                isShowing = false
            }
        })
        dialog.isVisible = true
    }
}
