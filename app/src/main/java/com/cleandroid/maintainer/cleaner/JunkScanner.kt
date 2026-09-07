package com.cleandroid.maintainer.cleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.cleandroid.maintainer.core.JunkCategory
import com.cleandroid.maintainer.core.JunkItem
import com.cleandroid.maintainer.core.ScanResult
import java.io.File

/**
 * Scanner principal. Varre apenas áreas acessíveis sem root.
 * Multi-versão:
 * - API 21-28: varredura direta em /sdcard
 * - API 29: requestLegacyExternalStorage permite varredura direta
 * - API 30+: exige MANAGE_EXTERNAL_STORAGE, senão limita a app-specific + MediaStore
 *
 * NUNCA apaga sem confirmação. Este scanner só LISTA; a exclusão é explícita.
 */
class JunkScanner(private val ctx: Context) {

    interface Progress { fun onDir(path: String, count: Int) }

    private val hiddenTrashNames = setOf(
        ".thumbnails", ".trash", ".trashed", ".cache", ".tmp",
        "thumbs.db", ".ds_store", ".nomedia.tmp"
    )
    private val tempExts = setOf("tmp", "temp", "log", "bak", "old", "dmp", "chk")
    private val maxDepth = 8
    private val maxFilesPerScan = 60_000

    fun scan(
        includeWhatsApp: Boolean = true,
        includeDuplicates: Boolean = false, // duplicados têm scanner próprio (hash é caro)
        progress: Progress? = null
    ): ScanResult {
        val t0 = System.currentTimeMillis()
        val items = mutableListOf<JunkItem>()

        // 1) Cache próprio (sempre permitido)
        items += ownCache()

        // 2) Raízes acessíveis
        val roots = accessibleRoots()
        val counter = Counter()
        for (root in roots) {
            walk(root, 0, object : Progress {
                override fun onDir(path: String, count: Int) { progress?.onDir(path, count) }
            }, items, includeWhatsApp, counter)
            if (counter.n >= maxFilesPerScan) break
        }

        // 3) Rastros de desinstalação: pastas cujo pacote não existe mais
        items += uninstallTraces(roots)

        return ScanResult(items, durationMs = System.currentTimeMillis() - t0)
    }

    private class Counter(var n: Int = 0)

    private fun ownCache(): List<JunkItem> {
        val out = mutableListOf<JunkItem>()
        listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).forEach { dir ->
            val size = dirSize(dir, 2)
            if (size > 0) out += JunkItem(
                dir.absolutePath, "Cache próprio (${dir.name})",
                size, JunkCategory.APP_CACHE
            )
        }
        // codeCacheDir existe desde API 21? getCodeCacheDir desde 21 sim.
        try {
            val code = ctx.codeCacheDir
            if (code.exists()) {
                val s = dirSize(code, 2)
                if (s > 0) out += JunkItem(code.absolutePath, "Code cache", s, JunkCategory.APP_CACHE)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun accessibleRoots(): List<File> {
        val list = mutableListOf<File>()
        // Armazenamento externo primário
        Environment.getExternalStorageDirectory()?.let { if (it.canRead()) list += it }
        // getExternalFilesDirs / app-specific não precisa scan profundo (é nosso)
        // Diretórios públicos padrão
        listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOCUMENTS
        ).forEach { type ->
            try {
                val f = Environment.getExternalStoragePublicDirectory(type)
                if (f.exists() && f.canRead() && list.none { it.canonicalPath == f.canonicalPath }) {
                    // já coberto pela raiz, evita duplicar; mantém só se raiz inacessível
                }
            } catch (_: Exception) {}
        }
        return list.distinctBy { runCatching { it.canonicalPath }.getOrNull() }
    }

    private fun walk(
        dir: File, depth: Int, progress: Progress,
        out: MutableList<JunkItem>, includeWhatsApp: Boolean, counter: Counter
    ) {
        if (depth > maxDepth || counter.n > maxFilesPerScan) return
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        progress.onDir(dir.absolutePath, counter.n)

        for (f in children) {
            counter.n++
            if (counter.n > maxFilesPerScan) return
            try {
                if (f.isDirectory) {
                    val name = f.name
                    // Pastas vazias
                    val inner = try { f.listFiles() } catch (_: Exception) { null }
                    if (inner != null && inner.isEmpty()) {
                        out += JunkItem(f.absolutePath, name, 0L, JunkCategory.EMPTY_FOLDERS)
                        continue
                    }
                    // Lixo oculto por nome
                    if (hiddenTrashNames.contains(name.lowercase()) || (name.startsWith(".") && isKnownJunkDot(name))) {
                        val s = dirSize(f, 3)
                        out += JunkItem(f.absolutePath, name, s, JunkCategory.HIDDEN_TRASH)
                        continue // não desce em lixo oculto gigante para ser rápido
                    }
                    // WhatsApp: delega categorização fina mas continua varrendo tamanhos
                    if (!includeWhatsApp && isWhatsAppPath(f)) continue
                    // Recursão, pulando Android/data e Android/obb sem permissão especial
                    // (Android 11+ bloqueia; listar gera SecurityException em alguns OEMs)
                    if ((name == "data" || name == "obb") && f.parentFile?.name == "Android") {
                        continue
                    }
                    walk(f, depth + 1, progress, out, includeWhatsApp, counter)
                } else {
                    val n = f.name.lowercase()
                    val ext = f.extension.lowercase()
                    val len = try { f.length() } catch (_: Exception) { 0L }
                    when {
                        ext == "apk" -> out += JunkItem(f.absolutePath, f.name, len, JunkCategory.RESIDUAL_APK)
                        tempExts.contains(ext) -> out += JunkItem(f.absolutePath, f.name, len, JunkCategory.TEMP_FILES)
                        n == "thumbs.db" || n == ".ds_store" -> out += JunkItem(f.absolutePath, f.name, len, JunkCategory.HIDDEN_TRASH)
                        isWhatsAppJunkFile(f) && includeWhatsApp ->
                            out += JunkItem(f.absolutePath, f.name, len, JunkCategory.WHATSAPP_JUNK, extra = "whatsapp")
                        isLargeStaleDownload(f) ->
                            out += JunkItem(f.absolutePath, f.name, len, JunkCategory.DOWNLOADS_LARGE)
                    }
                }
            } catch (_: Exception) { /* ignora arquivo problemático */ }
        }
    }

    private fun isKnownJunkDot(name: String): Boolean {
        val n = name.lowercase()
        return n == ".trash-1000" || n.startsWith(".tmp") || n == ".cache" ||
                n == ".thumbnails" || n == ".trashed" || n.endsWith(".tmp")
    }

    private fun isWhatsAppPath(f: File): Boolean {
        val p = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
        return p.contains("WhatsApp", ignoreCase = true)
    }

    private fun isWhatsAppJunkFile(f: File): Boolean {
        val p = (runCatching { f.parentFile?.canonicalPath }.getOrNull() ?: "").lowercase()
        if (!p.contains("whatsapp")) return false
        // .Statuses, .trash, Sent, Stickers, Backups antigos, .thumb
        return p.contains(".statuses") || p.contains(".trash") || p.contains("/sent") ||
                p.contains("sticker") || p.contains(".thumb") || f.name.startsWith("msgstore-") ||
                f.name.endsWith(".tmp")
    }

    private fun isLargeStaleDownload(f: File): Boolean {
        return try {
            val parent = runCatching { f.parentFile?.canonicalPath }.getOrNull() ?: ""
            if (!parent.contains("Download", ignoreCase = true)) return false
            val ageDays = (System.currentTimeMillis() - f.lastModified()) / 86_400_000L
            f.length() > 50L * 1024 * 1024 && ageDays > 90
        } catch (_: Exception) { false }
    }

    private fun uninstallTraces(roots: List<File>): List<JunkItem> {
        // Heurística: pastas em Android/data ou raiz com nome de pacote (com.xxx.yyy)
        // cujo pacote não está mais instalado.
        val out = mutableListOf<JunkItem>()
        val pm = ctx.packageManager
        val candidates = mutableListOf<File>()
        roots.forEach { root ->
            listOf(File(root, "Android/data"), root).forEach { base ->
                try {
                    base.listFiles()?.forEach { f ->
                        if (f.isDirectory && f.name.count { it == '.' } >= 2 && !f.name.startsWith(".")) {
                            candidates += f
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        for (c in candidates.distinctBy { it.absolutePath }.take(500)) {
            if (isPackageInstalled(pm, c.name)) continue
            // Evita falsos positivos: só marca se parece pacote e tem +7 dias sem modificação
            val ageDays = (System.currentTimeMillis() - c.lastModified()) / 86_400_000L
            if (ageDays >= 7) {
                out += JunkItem(
                    c.absolutePath, "${c.name} (app desinstalado?)",
                    dirSize(c, 2), JunkCategory.UNINSTALL_TRACES
                )
            }
        }
        return out
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) { false }
    }

    internal fun dirSize(dir: File, depth: Int): Long {
        if (depth < 0) return 0L
        var total = 0L
        var count = 0
        fun rec(d: File, dp: Int) {
            if (dp < 0 || count > 5000) return
            val kids = try { d.listFiles() } catch (_: Exception) { return } ?: return
            for (k in kids) {
                count++
                if (count > 5000) return
                try {
                    if (k.isFile) total += k.length()
                    else if (dp > 0) rec(k, dp - 1)
                } catch (_: Exception) {}
            }
        }
        try { rec(dir, depth) } catch (_: Exception) {}
        return total
    }

    /** Apaga com segurança: bloqueia paths críticos */
    fun delete(item: JunkItem): Boolean {
        val f = File(item.path)
        if (isProtected(f)) return false
        return try {
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        } catch (_: Exception) { false }
    }

    private fun isProtected(f: File): Boolean {
        val p = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
        // Nunca tocar em sistema, no próprio APK, ou raiz absoluta
        if (p == "/" || p == "/system" || p.startsWith("/system/")) return true
        if (p.startsWith("/data/data/com.cleandroid.maintainer/apks")) return false
        // protege pasta do próprio app
        if (p.contains("com.cleandroid.maintainer") && p.contains("Android/data")) return true
        // protege DCIM/Camera recente? Não bloqueamos, mas exigimos confirmação na UI.
        return false
    }
}
