package com.cleandroid.maintainer.cleaner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Limpeza guiada via Acessibilidade (opcional, sem root/Shizuku).
 * Fluxo: usuário toca "Limpeza profunda guiada" -> app abre tela de
 * armazenamento de cada app -> este serviço clica em "Limpar cache"
 * automaticamente após confirmação.
 *
 * ATIVAÇÃO: Config > Acessibilidade > CleanDroid > Ativar.
 * O serviço SÓ age quando o usuário iniciou a limpeza (flag userRequested).
 */
class CleanAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var userRequested: Boolean = false
        @Volatile var clicksDone: Int = 0
    }

    override fun onServiceConnected() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!userRequested) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        try {
            val root = rootInActiveWindow ?: return
            // Procura botões "Limpar cache" / "Clear cache" (PT/EN)
            val targets = mutableListOf<AccessibilityNodeInfo>()
            for (term in listOf("Limpar cache", "LIMPAR CACHE", "Clear cache", "CLEAR CACHE")) {
                targets += root.findAccessibilityNodeInfosByText(term)
            }
            for (node in targets) {
                var n: AccessibilityNodeInfo? = node
                // sobe até ancestral clicável
                while (n != null && !n.isClickable) n = n.parent
                if (n != null && n.isEnabled) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clicksDone++
                    break // um clique por evento para não duplicar
                }
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        userRequested = false
        return super.onUnbind(intent)
    }
}
