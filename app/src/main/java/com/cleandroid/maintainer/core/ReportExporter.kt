package com.cleandroid.maintainer.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Exporta relatório .txt da varredura (compatível Scoped Storage). */
object ReportExporter {

    fun export(ctx: Context, text: String, prefix: String = "CleanDroid"): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = "${prefix}-$stamp.txt"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CleanDroid")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                ctx.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) }
                // compartilha
                try {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "text/plain"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Relatório").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (_: Exception) {}
                "Salvo em Downloads/CleanDroid/$name"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CleanDroid")
                dir.mkdirs()
                val f = File(dir, name)
                f.writeText(text)
                "Salvo em ${f.absolutePath}"
            }
        } catch (e: Exception) {
            "Falha ao exportar: ${e.message}"
        }
    }

    fun buildReport(statusText: String, extra: String = ""): String {
        return buildString {
            appendLine("=== CleanDroid — Relatório ===")
            appendLine("Data: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}")
            appendLine("Android API: ${VersionCompat.sdk} (${VersionCompat.label()})")
            appendLine()
            appendLine(statusText)
            if (extra.isNotBlank()) { appendLine(); appendLine(extra) }
        }
    }
}
