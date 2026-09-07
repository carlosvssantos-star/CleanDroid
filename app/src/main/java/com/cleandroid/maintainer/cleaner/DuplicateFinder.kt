package com.cleandroid.maintainer.cleaner

import android.os.Environment
import com.cleandroid.maintainer.core.JunkCategory
import com.cleandroid.maintainer.core.JunkItem
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Detector de duplicados em 2 fases (rápido e econômico):
 * 1) agrupa por tamanho (arquivos de mesmo tamanho são candidatos)
 * 2) confirma com hash SHA-256 parcial (primeiros 256KB) + total se necessário
 * Limites para não travar celular: max 20k arquivos, pula <4KB e >2GB.
 */
class DuplicateFinder {

    data class Group(val hash: String, val size: Long, val files: List<File>) {
        fun wastedBytes(): Long = if (files.size > 1) size * (files.size - 1) else 0L
    }

    fun find(onProgress: (Int) -> Unit = {}): List<Group> {
        val roots = listOfNotNull(
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        ).filter { it.canRead() }

        val bySize = HashMap<Long, MutableList<File>>()
        var count = 0
        fun collect(dir: File, depth: Int) {
            if (depth > 6 || count > 20_000) return
            val kids = try { dir.listFiles() } catch (_: Exception) { return } ?: return
            for (k in kids) {
                if (count % 500 == 0) onProgress(count)
                try {
                    if (k.isDirectory) {
                        if (k.name == "Android" && depth == 0) {
                            // entra só em Android/media (WhatsApp etc), pula data/obb
                            val media = File(k, "media")
                            if (media.exists()) collect(media, depth + 1)
                            continue
                        }
                        if (k.name.startsWith(".")) continue
                        collect(k, depth + 1)
                    } else {
                        count++
                        val len = k.length()
                        if (len < 4096 || len > 2L * 1024 * 1024 * 1024) continue
                        if (k.name.startsWith(".")) continue
                        bySize.getOrPut(len) { mutableListOf() }.add(k)
                    }
                } catch (_: Exception) {}
            }
        }
        roots.forEach { collect(it, 0) }

        val groups = mutableListOf<Group>()
        for ((size, files) in bySize) {
            if (files.size < 2 || files.size > 50) continue
            // hash parcial
            val byPartial = files.groupBy { runCatching { partialHash(it, 256 * 1024) }.getOrNull() ?: it.absolutePath + it.lastModified() }
            for ((ph, sub) in byPartial) {
                if (sub.size < 2) continue
                // hash total para confirmar
                val byFull = sub.groupBy { runCatching { fullHash(it) }.getOrNull() ?: it.absolutePath }
                for ((fh, dupes) in byFull) {
                    if (dupes.size >= 2) groups += Group("$ph:$fh", size, dupes.sortedBy { it.absolutePath })
                }
            }
        }
        return groups.sortedByDescending { it.wastedBytes() }.take(200)
    }

    fun toJunkItems(groups: List<Group>): List<JunkItem> {
        // Mantém 1 cópia, sugere apagar o resto
        val out = mutableListOf<JunkItem>()
        for (g in groups) {
            g.files.drop(1).forEach { f ->
                out += JunkItem(f.absolutePath, f.name, g.size, JunkCategory.DUPLICATES, extra = "manter: ${g.files.first().absolutePath}")
            }
        }
        return out
    }

    private fun partialHash(f: File, n: Int): String {
        FileInputStream(f).use { ins ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8192)
            var remaining = n
            while (remaining > 0) {
                val r = ins.read(buf, 0, minOf(buf.size, remaining))
                if (r <= 0) break
                md.update(buf, 0, r)
                remaining -= r
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun fullHash(f: File): String {
        FileInputStream(f).use { ins ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(32 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
