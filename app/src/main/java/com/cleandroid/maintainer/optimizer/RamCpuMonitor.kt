package com.cleandroid.maintainer.optimizer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.RandomAccessFile

/** Monitor de RAM/CPU multi-versão. Sem root, CPU é estimativa via /proc/stat. */
class RamCpuMonitor(private val ctx: Context) {

    data class RamInfo(
        val totalMb: Long, val availMb: Long, val usedMb: Long,
        val usedPct: Int, val lowMemory: Boolean, val thresholdMb: Long
    )

    data class CpuInfo(val usagePct: Float?, val cores: Int, val freqsMhz: List<Long>)

    fun ram(): RamInfo {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = if (mi.totalMem > 0) mi.totalMem else estimateTotalRam()
        val avail = mi.availMem
        val used = (total - avail).coerceAtLeast(0)
        val pct = if (total > 0) ((used * 100) / total).toInt() else 0
        return RamInfo(total / 1048576, avail / 1048576, used / 1048576, pct, mi.lowMemory, mi.threshold / 1048576)
    }

    private fun estimateTotalRam(): Long {
        // Fallback API 21-15 sem totalMem (raro): lê /proc/meminfo
        return try {
            RandomAccessFile("/proc/meminfo", "r").use { raf ->
                val line = raf.readLine() ?: return 4L * 1024 * 1024 * 1024
                val digits = line.filter { it.isDigit() }
                val kb = digits.toLongOrNull() ?: 0L
                kb * 1024
            }
        } catch (_: Exception) { 4L * 1024 * 1024 * 1024 }
    }

    /** PIDs mais pesados (getRunningAppProcesses é limitado desde Nougat, mas ainda útil) */
    fun topProcesses(limit: Int = 10): List<ProcRank> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val procs = try { am.runningAppProcesses } catch (_: Exception) { null } ?: return emptyList()
        val out = mutableListOf<ProcRank>()
        for (p in procs.take(40)) {
            try {
                val pids = intArrayOf(p.pid)
                val mem = am.getProcessMemoryInfo(pids).firstOrNull()
                val pssKb = mem?.totalPss ?: 0
                out += ProcRank(p.processName, importanceLabel(p.importance), pssKb.toLong())
            } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.pssKb }.take(limit)
    }

    data class ProcRank(val name: String, val importance: String, val pssKb: Long)

    private fun importanceLabel(imp: Int): String = when (imp) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "1º plano"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visível"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Serviço"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "2º plano"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "Vazio/cache"
        else -> "Imp=$imp"
    }

    /** Leitura dupla de /proc/stat com 360ms de intervalo */
    fun cpu(): CpuInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = readFreqs(cores)
        val usage = readCpuUsage()
        return CpuInfo(usage, cores, freqs)
    }

    private fun readCpuUsage(): Float? {
        return try {
            fun snapshot(): LongArray {
                RandomAccessFile("/proc/stat", "r").use { raf ->
                    val parts = raf.readLine().trim().split("\\s+".toRegex())
                    // cpu user nice system idle iowait irq softirq steal
                    return LongArray(8) { i -> parts.getOrNull(i + 1)?.toLongOrNull() ?: 0L }
                }
            }
            val a = snapshot()
            Thread.sleep(360)
            val b = snapshot()
            val idleA = a[3] + a[4]; val idleB = b[3] + b[4]
            val totalA = a.sum(); val totalB = b.sum()
            val totalD = (totalB - totalA).toFloat()
            val idleD = (idleB - idleA).toFloat()
            if (totalD <= 0) null else ((totalD - idleD) / totalD * 100f).coerceIn(0f, 100f)
        } catch (_: Exception) { null }
    }

    private fun readFreqs(cores: Int): List<Long> {
        val out = mutableListOf<Long>()
        for (i in 0 until cores.coerceAtMost(8)) {
            try {
                RandomAccessFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq", "r").use {
                    out += (it.readLine()?.trim()?.toLongOrNull() ?: 0L) / 1000
                }
            } catch (_: Exception) { out += 0L }
        }
        return out
    }

    /** "Otimizar": mata 2º plano + sugere trim. Não promete milagre — Android gerencia RAM sozinho. */
    fun optimize(): OptimizeResult {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var killed = 0
        val before = ram().availMb
        try {
            val procs = am.runningAppProcesses ?: emptyList()
            for (p in procs) {
                val imp = p.importance
                if (imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND ||
                    imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY ||
                    imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                ) {
                    try {
                        am.killBackgroundProcesses(p.processName)
                        killed++
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // Sugestão de GC do próprio processo
        try { System.gc() } catch (_: Exception) {}
        Thread.sleep(600)
        val after = ram().availMb
        return OptimizeResult(killed, before, after, (after - before).coerceAtLeast(0))
    }

    data class OptimizeResult(val killed: Int, val beforeAvailMb: Long, val afterAvailMb: Long, val freedMb: Long)

    @Suppress("DEPRECATION")
    fun internalStorage(): StorageInfo {
        val path = android.os.Environment.getDataDirectory()
        val stat = android.os.StatFs(path.path)
        val (blockSize, totalB, availB) = if (Build.VERSION.SDK_INT >= 18) {
            Triple(stat.blockSizeLong, stat.blockCountLong, stat.availableBlocksLong)
        } else {
            Triple(stat.blockSize.toLong(), stat.blockCount.toLong(), stat.availableBlocks.toLong())
        }
        val total = totalB * blockSize
        val avail = availB * blockSize
        return StorageInfo(total, total - avail, avail)
    }

    data class StorageInfo(val total: Long, val used: Long, val free: Long)
}
