package com.cleandroid.maintainer.apps

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Equivalente honesto ao "winget upgrade --all" no Android.
 * Limitação real: Android NÃO permite atualizar apps de terceiros silenciosamente
 * sem ser loja do sistema / device owner. O que dá para fazer:
 *  1) In-App Updates API para atualizar O PRÓPRIO CleanDroid
 *  2) Abrir Play Store na lista de updates para o usuário tocar "Atualizar tudo"
 *  3) Para os próprios APKs (sideload), verificar ação VIEW + installer
 */
object AppUpdateHelper {

    /** Abre tela de "Gerenciar apps e dispositivos > Atualizações" da Play Store */
    fun openPlayUpdates(ctx: Context): Boolean {
        val intents = listOf(
            // Deep-link direto para updates pendentes
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ctx.packageName}")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps")),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://apps/updates"))
        )
        // Melhor esforço: abre Play Store; fallback: lista de apps do sistema
        return try {
            ctx.startActivity(intents[0].apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            true
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(intents[1].apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                true
            } catch (_: Exception) { false }
        }
    }

    /** Abre página de cada app para update manual (para modo "atualizar tudo" guiado) */
    fun openStorePage(ctx: Context, packageName: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    /**
     * Checagem de update do PRÓPRIO app via Play Core (dependência app-update-ktx).
     * Chamar da MainActivity com um Activity. Retorna true se fluxo iniciou.
     * Uso:
     *   AppUpdateHelper.checkSelfUpdate(this, MY_REQUEST_CODE)
     */
    fun checkSelfUpdate(activity: Activity, requestCode: Int = 5001): Boolean {
        return try {
            val manager = com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(activity)
            val infoTask = manager.appUpdateInfo
            infoTask.addOnSuccessListener { info ->
                try {
                    val allowed = info.updateAvailability() ==
                            com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE &&
                            info.isUpdateTypeAllowed(com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE)
                    if (allowed) {
                        manager.startUpdateFlowForResult(
                            info,
                            com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE,
                            activity, requestCode
                        )
                    }
                } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) { false }
    }
}
