package com.cleandroid.maintainer.cleaner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.cleandroid.maintainer.core.PrivilegeManager
import com.cleandroid.maintainer.core.ShizukuHelper

/**
 * Limpeza profunda pela escada de privilégio:
 *  1) ROOT (`su`) — trim-caches global automático, o mais abrangente.
 *  2) Shizuku — trim-caches via API (fallback sem root).
 *  3) Guiada — abre a tela de cada app e (com Acessibilidade) clica "Limpar cache",
 *     ou o usuário clica manualmente.
 */
object DeepCleanOrchestrator {

    fun start(ctx: Context, packages: List<String>) {
        // 1) Root?
        if (PrivilegeManager.hasRoot()) {
            Toast.makeText(ctx, "Root: limpando cache de todos os apps...", Toast.LENGTH_LONG).show()
            Thread {
                val (code, _) = PrivilegeManager.trimAllCachesRoot()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(
                        ctx,
                        if (code == 0) "Root: cache global limpo com sucesso."
                        else "Root: falhou (código $code). Tentando modo guiado...",
                        Toast.LENGTH_LONG
                    ).show()
                    if (code != 0) startGuided(ctx, packages)
                }
            }.start()
            return
        }
        // 2) Shizuku?
        if (runCatching { ShizukuHelper.isPermissionGranted() }.getOrDefault(false)) {
            val (code, _) = ShizukuHelper.trimAllCaches()
            Toast.makeText(
                ctx,
                if (code == 0) "Shizuku: cache global limpo." else "Shizuku: falhou. Indo p/ modo guiado...",
                Toast.LENGTH_LONG
            ).show()
            if (code == 0) return
        } else if (ShizukuHelper.isInstalled(ctx)) {
            ShizukuHelper.requestPermission()
            Toast.makeText(ctx, "Autorize no Shizuku e toque de novo.", Toast.LENGTH_LONG).show()
            return
        }
        // 3) Guiada
        startGuided(ctx, packages)
    }

    private fun startGuided(ctx: Context, packages: List<String>) {
        if (isAccessibilityEnabled(ctx)) {
            CleanAccessibilityService.userRequested = true
            CleanAccessibilityService.clicksDone = 0
            Toast.makeText(ctx, "Limpeza guiada: abrindo telas dos apps...", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                ctx,
                "Sem root/Shizuku: abrindo telas para limpeza manual (ative Acessibilidade p/ automático).",
                Toast.LENGTH_LONG
            ).show()
        }
        openNext(ctx, packages, 0)
    }

    private fun openNext(ctx: Context, pkgs: List<String>, idx: Int) {
        if (idx >= pkgs.size || idx >= 15) { // limite 15 por rodada
            CleanAccessibilityService.userRequested = false
            return
        }
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${pkgs[idx]}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            openNext(ctx, pkgs, idx + 1)
        }, 2500)
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains(ctx.packageName)
        } catch (_: Exception) { false }
    }

    fun stop() { CleanAccessibilityService.userRequested = false }
}
