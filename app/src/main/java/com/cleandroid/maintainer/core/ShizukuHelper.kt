package com.cleandroid.maintainer.core

import android.content.Context
import android.content.pm.PackageManager

/**
 * Integração opcional com Shizuku (https://shizuku.rikka.app/)
 * Permite limpeza profunda SEM root: executar `pm trim-caches` e remover
 * arquivos inacessíveis via shell com UID de sistema.
 *
 * Funciona por reflection + biblioteca Shizuku (declarada no build.gradle).
 * Se Shizuku não instalado, todos os métodos retornam false / fallback seguro.
 */
object ShizukuHelper {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    fun isInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (_: Exception) { false }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            // Chamada via API Shizuku; se lib ausente, ClassNotFound -> false
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val m = clazz.getMethod("checkSelfPermission")
            (m.invoke(null) as? Int) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
    }

    fun requestPermission(code: Int = 9001) {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            clazz.getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, code)
        } catch (_: Throwable) {}
    }

    /**
     * Executa comando shell com privilégio Shizuku.
     * Retorna Pair(exitCode, output). exitCode -1 = indisponível.
     */
    fun exec(cmd: String): Pair<Int, String> {
        return try {
            val procClazz = Class.forName("rikka.shizuku.Shizuku")
            // rikka.shizuku.Shizuku.newProcess(String[], String[], String)
            val m = procClazz.getMethod(
                "newProcess", Array<String>::class.java,
                Array<String>::class.java, String::class.java
            )
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = proc.inputStream.bufferedReader().readText() +
                    proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            code to out
        } catch (t: Throwable) {
            -1 to (t.message ?: "shizuku indisponível")
        }
    }

    /** Limpeza profunda de caches: equivale a `pm trim-caches 2G` (padrão AOSP) */
    fun trimAllCaches(): Pair<Int, String> {
        if (!isPermissionGranted()) return -1 to "Sem permissão Shizuku"
        // 2G = força liberação agressiva; 999G seria leve. Usamos 2G sob confirmação.
        return exec("pm trim-caches 2147483648")
    }

    /** Remove path via shell privilegiado (com trava de segurança) */
    fun privilegedDelete(path: String): Boolean {
        if (!isPermissionGranted()) return false
        // Bloqueios críticos
        if (path == "/" || path.startsWith("/system") || path.startsWith("/vendor")) return false
        if (path.contains("..")) return false
        val safe = "'${path.replace("'", "'\\''")}'"
        val (code, _) = exec("rm -rf $safe")
        return code == 0
    }
}
