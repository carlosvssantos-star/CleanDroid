package com.cleandroid.maintainer.core

import android.net.Uri

/** Categoria de lixo encontrada pelo scanner */
enum class JunkCategory(val title: String) {
    APP_CACHE("Cache de apps (próprio + temporários acessíveis)"),
    RESIDUAL_APK("APKs residuais (.apk soltos)"),
    TEMP_FILES("Arquivos temporários (.tmp/.temp/.log/.bak)"),
    EMPTY_FOLDERS("Pastas vazias"),
    HIDDEN_TRASH("Lixo oculto (pastas .thumbnails, .trash, .cache)"),
    UNINSTALL_TRACES("Rastros de desinstalações"),
    WHATSAPP_JUNK("Lixo do WhatsApp (figurinhas, status, backup, enviados)"),
    DUPLICATES("Arquivos duplicados (grupo)"),
    DOWNLOADS_LARGE("Downloads grandes / obsoletos")
}

/** Um item de lixo detectado */
data class JunkItem(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val category: JunkCategory,
    val uri: Uri? = null,
    val extra: String? = null
)

/** Resultado agregado do scan */
data class ScanResult(
    val items: List<JunkItem>,
    val totalBytes: Long = items.sumOf { it.sizeBytes },
    val durationMs: Long = 0L
) {
    fun byCategory(): Map<JunkCategory, List<JunkItem>> = items.groupBy { it.category }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
