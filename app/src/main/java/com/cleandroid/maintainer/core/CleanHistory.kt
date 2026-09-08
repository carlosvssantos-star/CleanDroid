package com.cleandroid.maintainer.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Histórico simples das limpezas (SharedPreferences). Alimenta o dashboard. */
object CleanHistory {

    private const val PREF = "clean_history"
    private const val KEY_LOG = "log" // "ts,freed,removed;..."
    private const val KEY_TOTAL = "total_freed"
    private const val MAX = 20

    data class Entry(val ts: Long, val freed: Long, val removed: Int)

    fun record(ctx: Context, freed: Long, removed: Int) {
        try {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val log = (p.getString(KEY_LOG, "") ?: "").split(";").filter { it.isNotBlank() }.toMutableList()
            log.add(0, "${System.currentTimeMillis()},$freed,$removed")
            p.edit()
                .putString(KEY_LOG, log.take(MAX).joinToString(";"))
                .putLong(KEY_TOTAL, p.getLong(KEY_TOTAL, 0L) + freed)
                .apply()
        } catch (_: Exception) {}
    }

    fun last(ctx: Context): Entry? {
        return try {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val first = (p.getString(KEY_LOG, "") ?: "").split(";").firstOrNull { it.isNotBlank() }
                ?: return null
            val parts = first.split(",")
            Entry(parts[0].toLong(), parts[1].toLong(), parts[2].toInt())
        } catch (_: Exception) { null }
    }

    fun totalFreed(ctx: Context): Long {
        return try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_TOTAL, 0L)
        } catch (_: Exception) { 0L }
    }

    fun lastLabel(ctx: Context): String {
        val e = last(ctx) ?: return "Nenhuma limpeza ainda"
        val whenStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(e.ts))
        return "Última: ${formatBytes(e.freed)} • ${e.removed} itens • $whenStr"
    }

    fun isFirstRun(ctx: Context): Boolean {
        return try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("first_done", false).not()
        } catch (_: Exception) { true }
    }

    fun markFirstDone(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("first_done", true).apply()
        } catch (_: Exception) {}
    }
}
