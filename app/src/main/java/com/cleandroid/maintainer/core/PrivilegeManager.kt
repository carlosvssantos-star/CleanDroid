package com.cleandroid.maintainer.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Escada de privilégio para limpeza profunda (sem Shizuku como obrigatório):
 *  1) ROOT (Magisk / KernelSU / APatch via `su`) — o mais abrangente:
 *     trim-caches do sistema + remoção de lixo inacessível, sem app auxiliar.
 *  2) Shizuku (fallback sem root) — mantido como alternativa.
 *  3) Limpeza guiada (Acessibilidade ou manual) — sempre disponível.
 *
 * Root aqui é a alternativa "mais recente e abrangente": KernelSU/APatch são o
 * padrão atual (2024+) e dão shell uid 0 direto, além do que o Shizuku alcança.
 */
object PrivilegeManager {

    enum class Level(val label: String) {
        ROOT("Root (acesso total)"),
        SHIZUKU("Shizuku (sem root)"),
        NONE("Sem privilégio (limpeza guiada)")
    }

    @Volatile private var rootCache: Boolean? = null

    /** Detecta `su` funcional (binário + `su -c id` retorna uid=0). Com cache. */
    fun hasRoot(): Boolean {
        rootCache?.let { return it }
        val ok = findSu() != null && testSu()
        rootCache = ok
        return ok
    }

    fun level(): Level = when {
        hasRoot() -> Level.ROOT
        runCatching { ShizukuHelper.isPermissionGranted() }.getOrDefault(false) -> Level.SHIZUKU
        else -> Level.NONE
    }

    fun describe(): String = when (level()) {
        Level.ROOT -> "Root detectado — limpeza profunda total disponível."
        Level.SHIZUKU -> "Shizuku autorizado — limpeza profunda disponível."
        Level.NONE -> "Sem root/Shizuku — use a limpeza guiada (abrimos as telas para você)."
    }

    private fun findSu(): String? {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/magisk/.core/bin/su", "/data/adb/magisk/busybox/su"
        )
        for (p in paths) {
            try { if (java.io.File(p).exists()) return p } catch (_: Exception) {}
        }
        // PATH (KernelSU costuma expor su no PATH do app? nem sempre; tenta direto)
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotBlank() && !out.contains("not found")) out.lineSequence().first().trim() else "su"
        } catch (_: Exception) { "su" }
    }

    private fun testSu(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(findSu() ?: "su", "-c", "id"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) { false }
    }

    /** Executa comando como root. Retorna Pair(exitCode, saída). */
    fun runAsRoot(cmd: String): Pair<Int, String> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(findSu() ?: "su", "-c", cmd))
            val out = proc.inputStream.bufferedReader().readText() +
                    proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            code to out
        } catch (t: Throwable) { -1 to (t.message ?: "root indisponível") }
    }

    /** Equivalente ao trim-caches global: libera cache de TODOS os apps (root). */
    fun trimAllCachesRoot(): Pair<Int, String> {
        // `cmd package trim-caches <bytes>` existe desde API 23; fallback pm (legado)
        val (c1, o1) = runAsRoot("cmd package trim-caches 2147483648")
        if (c1 == 0) return c1 to o1
        return runAsRoot("pm trim-caches 2147483648")
    }

    /** Remove path como root, com as mesmas travas de segurança do modo normal. */
    fun deleteAsRoot(path: String): Boolean {
        if (path == "/" || path.startsWith("/system") ||
            path.startsWith("/vendor") || path.startsWith("/product") ||
            path.contains("..")
        ) return false
        val safe = "'${path.replace("'", "'\\''")}'"
        return runAsRoot("rm -rf $safe").first == 0
    }

    /** Lista pacotes instalados (leves, sem infos sensíveis) p/ limpeza guiada. */
    fun installedPackages(ctx: Context, limit: Int = 60): List<String> {
        return try {
            val pm = ctx.packageManager
            val pkgs = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getInstalledPackages(0)
            }
            pkgs.mapNotNull { it.applicationInfo }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName }
                .take(limit)
        } catch (_: Exception) { emptyList() }
    }
}
