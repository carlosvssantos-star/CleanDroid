package com.cleandroid.maintainer.cleaner

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.cleandroid.maintainer.apps.AppManager

/**
 * Orquestra limpeza profunda guiada:
 * 1) Tenta Shizuku (rápido, automático)
 * 2) Senão, usa AccessibilityService (abre telas e auto-clica)
 * 3) Senão, abre telas manualmente (usuário clica)
 */
object DeepCleanOrchestrator {

    fun start(ctx: Context, appManager: AppManager, packages: List<String>) {
        // 1) Shizuku?
        try {
            val shizukuClazz = Class.forName("com.cleandroid.maintainer.core.ShizukuHelper")
            val granted = shizukuClazz.getMethod("isPermissionGranted").invoke(null) as Boolean
            if (granted) {
                val res = shizukuClazz.getMethod("trimAllCaches").invoke(null) as Pair<*, *>
                Toast.makeText(ctx, "Shizuku: trim-caches exit=${res.first}", Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Throwable) {}

        // 2) Acessibilidade ativa?
        if (isAccessibilityEnabled(ctx)) {
            CleanAccessibilityService.userRequested = true
            CleanAccessibilityService.clicksDone = 0
            Toast.makeText(ctx, "Limpeza guiada iniciada: abrindo telas de apps...", Toast.LENGTH_LONG).show()
            openNext(ctx, appManager, packages, 0)
        } else {
            // 3) Manual
            Toast.makeText(ctx, "Ative Acessibilidade ou Shizuku para automático. Abrindo manual...", Toast.LENGTH_LONG).show()
            openNext(ctx, appManager, packages, 0)
        }
    }

    private fun openNext(ctx: Context, appManager: AppManager, pkgs: List<String>, idx: Int) {
        if (idx >= pkgs.size || idx >= 15) { // limite 15 por rodada p/ não cansar
            CleanAccessibilityService.userRequested = false
            return
        }
        try {
            ctx.startActivity(appManager.openAppDetails(pkgs[idx]).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
        // Agenda próximo com delay: usa handler simples
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (CleanAccessibilityService.userRequested || true) openNext(ctx, appManager, pkgs, idx + 1)
        }, 2500)
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            enabled.contains(ctx.packageName)
        } catch (_: Exception) { false }
    }

    fun stop() { CleanAccessibilityService.userRequested = false }
}
