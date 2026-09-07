package com.cleandroid.maintainer.apps

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.cleandroid.maintainer.core.VersionCompat

/** Gestão de apps: lista, tamanho, uso, desinstalação, atalhos para telas do sistema. */
class AppManager(private val ctx: Context) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val sizeBytes: Long,
        val lastUsedAgo: String?,
        val version: String
    )

    fun listInstalled(includeSystem: Boolean = false, limit: Int = 300): List<AppEntry> {
        val pm = ctx.packageManager
        val pkgs = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_META_DATA)
            }
        } catch (_: Exception) { return emptyList() }

        val usage = lastUsedMap()
        val out = mutableListOf<AppEntry>()
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSys && !includeSystem) continue
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pi.packageName)
            val ver = pi.versionName ?: "?"
            val size = appSize(ai)
            val last = usage[pi.packageName]?.let { ago(it) }
            out += AppEntry(pi.packageName, label, isSys, size, last, ver)
            if (out.size >= limit) break
        }
        return out.sortedByDescending { it.sizeBytes }
    }

    private fun appSize(ai: ApplicationInfo): Long {
        // StorageStatsManager exige API 26+ e pode lançar; fallback: sourceDir.length
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                val uuid = ssm.getUuidForPath(java.io.File(ai.sourceDir))
                // queryStatsForPackage pode exigir permissão; captura exceção
                ssm.queryStatsForPackage(uuid, ai.packageName, android.os.Process.myUserHandle()).let {
                    it.appBytes + it.cacheBytes + it.dataBytes
                }
            } else {
                java.io.File(ai.sourceDir).length()
            }
        } catch (_: Exception) {
            runCatching { java.io.File(ai.sourceDir).length() }.getOrDefault(0L)
        }
    }

    private fun lastUsedMap(): Map<String, Long> {
        return try {
            if (!hasUsagePermission()) return emptyMap()
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 30L * 86_400_000L
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                ?.associate { it.packageName to it.lastTimeUsed } ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun hasUsagePermission(): Boolean {
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION") appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    private fun ago(lastUsed: Long): String? {
        if (lastUsed <= 0) return "nunca"
        val d = (System.currentTimeMillis() - lastUsed) / 86_400_000L
        return when {
            d < 1 -> "hoje"
            d == 1L -> "há 1 dia"
            d < 30 -> "há $d dias"
            d < 365 -> "há ${d / 30} meses"
            else -> "há ${d / 365} anos"
        }
    }

    // ---- Ações (intents do sistema, funcionam em todas as versões) ----

    fun openAppDetails(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Android não permite limpar cache alheio sem root; abre tela para limpeza manual */
    fun openStorageSettings(packageName: String): Intent = openAppDetails(packageName)

    fun uninstall(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun openPlayStore(packageName: String): Intent = try {
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } catch (_: Exception) {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
