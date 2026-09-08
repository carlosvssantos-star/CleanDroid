package com.cleandroid.maintainer.cleaner

import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.PriorityQueue

/**
 * Analisador de armazenamento: resumo (total/livre) + maiores arquivos.
 * Base do "dashboard" da tela inicial e do recurso "Arquivos grandes".
 */
object StorageAnalyzer {

    data class Summary(val total: Long, val used: Long, val free: Long) {
        val usedPct: Int get() = if (total > 0) ((used * 100) / total).toInt() else 0
    }

    fun summary(): Summary {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val (total, free) = if (Build.VERSION.SDK_INT >= 18) {
                stat.blockCountLong * stat.blockSizeLong to
                        stat.availableBlocksLong * stat.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                stat.blockCount.toLong() * stat.blockSize.toLong() to
                        @Suppress("DEPRECATION")
                        stat.availableBlocks.toLong() * stat.blockSize.toLong()
            }
            Summary(total, total - free, free)
        } catch (_: Exception) { Summary(0, 0, 0) }
    }

    data class BigFile(val file: File, val size: Long)

    /**
     * Varre o armazenamento compartilhado e devolve os N maiores arquivos
     * acima de [minBytes]. Usa heap mínima p/ não estourar memória.
     */
    fun largestFiles(
        limit: Int = 15,
        minBytes: Long = 50L * 1024 * 1024,
        onProgress: (Int) -> Unit = {}
    ): List<BigFile> {
        val heap = PriorityQueue<BigFile>(limit + 1) { a, b -> a.size.compareTo(b.size) }
        var seen = 0
        fun offer(f: File) {
            val len = runCatching { f.length() }.getOrDefault(0L)
            if (len < minBytes) return
            heap.offer(BigFile(f, len))
            if (heap.size > limit) heap.poll()
        }
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || seen > 25_000) return
            val kids = try { dir.listFiles() } catch (_: Exception) { return } ?: return
            for (k in kids) {
                try {
                    if (k.isDirectory) {
                        if (k.name == "Android" && depth == 0) continue // pula data/obb do sistema
                        if (k.name.startsWith(".")) continue
                        walk(k, depth + 1)
                    } else {
                        seen++
                        if (seen % 2000 == 0) onProgress(seen)
                        if (!k.name.startsWith(".")) offer(k)
                    }
                } catch (_: Exception) {}
            }
        }
        runCatching { Environment.getExternalStorageDirectory() }?.getOrNull()
            ?.takeIf { it.canRead() }?.let { walk(it, 0) }
        return heap.sortedByDescending { it.size }
    }
}
