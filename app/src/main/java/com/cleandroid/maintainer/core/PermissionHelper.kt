package com.cleandroid.maintainer.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Centraliza pedidos de permissão por versão.
 * Sem root o app NÃO consegue limpar cache de terceiros diretamente
 * (Android bloqueia desde 8.0). O fluxo correto é:
 *  - limpar o próprio cache + arquivos acessíveis via SAF/MediaStore
 *  - guiar usuário para tela de cada app para limpar cache manual
 *  - opcional: AccessibilityService ou integração Shizuku/root para avançado
 */
object PermissionHelper {

    fun storagePermissions(): Array<String> {
        return when {
            VersionCompat.sdk >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            VersionCompat.sdk >= 23 -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    fun hasStorageBasics(ctx: Context): Boolean =
        storagePermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

    fun hasAllFilesAccess(): Boolean =
        if (VersionCompat.sdk >= 30) Environment.isExternalStorageManager() else true

    fun intentAllFilesAccess(): Intent =
        if (VersionCompat.sdk >= 30) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:com.cleandroid.maintainer")
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)

    fun hasUsageStats(ctx: Context): Boolean {
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    fun intentUsageStats(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
