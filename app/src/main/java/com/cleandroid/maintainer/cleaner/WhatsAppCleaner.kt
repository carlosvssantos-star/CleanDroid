package com.cleandroid.maintainer.cleaner

import android.os.Environment
import com.cleandroid.maintainer.core.JunkCategory
import com.cleandroid.maintainer.core.JunkItem
import java.io.File

/**
 * Limpeza de WhatsApp com categorias seguras.
 * O WhatsApp muda pastas por época/versão:
 *  - Legado: /WhatsApp/...
 *  - Novo: /Android/media/com.whatsapp/WhatsApp/...
 *  - Business: com.whatsapp.w4b
 */
object WhatsAppCleaner {

    data class WhatsCategory(val id: String, val label: String, val safe: Boolean, val hint: String)

    val categories = listOf(
        WhatsCategory("status", "Status vistos (.Statuses)", true, "Status somem em 24h, seguro apagar"),
        WhatsCategory("sent", "Mídias 'Enviadas' duplicadas (Sent)", true, "Cópias em WhatsApp Images/Sent etc"),
        WhatsCategory("stickers", "Figurinhas cache", true, "Recriadas sob demanda"),
        WhatsCategory("thumbs", "Miniaturas (.thumbnails/.thumb)", true, "Recriadas pela galeria"),
        WhatsCategory("voice_old", "Áudios antigos +30 dias", false, "Requer confirmação - pode ter áudio importante"),
        WhatsCategory("backup_old", "Backups msgstore antigos", false, "Mantenha o mais recente!"),
        WhatsCategory("documents_old", "Documentos antigos +90 dias", false, "PDFs/docs podem ser importantes")
    )

    private val pkgNames = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun roots(): List<File> {
        val out = mutableListOf<File>()
        val ext = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return out
        out += File(ext, "WhatsApp")
        for (pkg in pkgNames) {
            out += File(ext, "Android/media/$pkg/WhatsApp")
        }
        return out.filter { it.exists() }
    }

    fun scan(selected: Set<String> = setOf("status", "sent", "stickers", "thumbs")): List<JunkItem> {
        val out = mutableListOf<JunkItem>()
        for (root in roots()) {
            scanRoot(root, out, selected)
        }
        return out
    }

    private fun scanRoot(root: File, out: MutableList<JunkItem>, sel: Set<String>) {
        val all = allFiles(root, 6, 15_000)
        for (f in all) {
            val p = f.absolutePath.replace('\\', '/')
            val pl = p.lowercase()
            val len = runCatching { f.length() }.getOrDefault(0L)
            when {
                sel.contains("status") && pl.contains(".statuses/") ->
                    out += JunkItem(f.absolutePath, "Status: ${f.name}", len, JunkCategory.WHATSAPP_JUNK, extra = "status")
                sel.contains("sent") && (pl.contains("/sent/") || pl.contains("/sent ")) ->
                    out += JunkItem(f.absolutePath, "Enviada: ${f.name}", len, JunkCategory.WHATSAPP_JUNK, extra = "sent")
                sel.contains("stickers") && (pl.contains("sticker") || pl.contains("figurinha")) ->
                    out += JunkItem(f.absolutePath, f.name, len, JunkCategory.WHATSAPP_JUNK, extra = "stickers")
                sel.contains("thumbs") && (pl.contains(".thumbnail") || pl.contains("/.thumb") || pl.contains(".thumb/")) ->
                    out += JunkItem(f.absolutePath, f.name, len, JunkCategory.WHATSAPP_JUNK, extra = "thumbs")
                sel.contains("voice_old") && (pl.contains("voice notes") || pl.contains("ptt") || pl.contains("audio")) && isOlderThan(f, 30) ->
                    out += JunkItem(f.absolutePath, "Áudio antigo: ${f.name}", len, JunkCategory.WHATSAPP_JUNK, extra = "voice_old")
                sel.contains("backup_old") && f.name.startsWith("msgstore-") ->
                    out += JunkItem(f.absolutePath, f.name, len, JunkCategory.WHATSAPP_JUNK, extra = "backup_old")
                sel.contains("documents_old") && pl.contains("whatsapp documents/") && isOlderThan(f, 90) ->
                    out += JunkItem(f.absolutePath, f.name, len, JunkCategory.WHATSAPP_JUNK, extra = "documents_old")
            }
        }
        // msgstore: mantém o mais recente, sugere resto (se categoria ativa)
        if (sel.contains("backup_old")) {
            val stores = out.filter { it.extra == "backup_old" }.sortedBy { File(it.path).lastModified() }
            if (stores.size > 1) {
                // remove o mais recente da lista de exclusão
                out.remove(stores.last())
            }
        }
    }

    private fun isOlderThan(f: File, days: Int): Boolean {
        return (System.currentTimeMillis() - runCatching { f.lastModified() }.getOrDefault(0L)) > days * 86_400_000L
    }

    private fun allFiles(root: File, maxDepth: Int, max: Int): List<File> {
        val out = mutableListOf<File>()
        fun rec(d: File, depth: Int) {
            if (depth > maxDepth || out.size > max) return
            val kids = try { d.listFiles() } catch (_: Exception) { return } ?: return
            for (k in kids) {
                if (out.size > max) return
                try {
                    if (k.isDirectory) rec(k, depth + 1) else out += k
                } catch (_: Exception) {}
            }
        }
        rec(root, 0)
        return out
    }
}
