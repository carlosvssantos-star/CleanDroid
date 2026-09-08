package com.cleandroid.maintainer.core

import android.net.Uri

/** Categoria de lixo encontrada pelo scanner.
 * @param safe true = seguro por padrão (marcado); false = exige opt-in explícito
 * @param desc explicação simples mostrada antes de apagar */
enum class JunkCategory(val title: String, val safe: Boolean, val desc: String) {
    APP_CACHE("Cache próprio do CleanDroid", true,
        "Arquivos temporários criados pelo próprio app. Apagar é 100% seguro."),
    RESIDUAL_APK("APKs esquecidos", true,
        "Instaladores .apk soltos em Downloads e pastas. Se o app já está instalado, o .apk é dispensável. Confira a lista antes."),
    TEMP_FILES("Temporários (.tmp/.log/.bak)", true,
        "Restos de instalações e erros. Programas recriam se precisar. Seguro."),
    EMPTY_FOLDERS("Pastas vazias", true,
        "Pastas sem nada dentro. Ocupam 0 bytes; remover só organiza."),
    HIDDEN_TRASH("Lixo oculto", true,
        "Miniaturas e lixeiras escondidas (.thumbnails, .trash). O sistema recria quando necessário."),
    UNINSTALL_TRACES("Restos de apps desinstalados", true,
        "Pastas com nome de aplicativo que não existe mais há 7+ dias. Seguro."),
    WHATSAPP_JUNK("WhatsApp (selecionado por você)", false,
        "Só entra o que você marcou na tela do WhatsApp. Status e 'Enviadas' são seguros; áudios e backups pedem atenção."),
    DUPLICATES("Duplicados (1 cópia mantida)", false,
        "Arquivos idênticos por conteúdo (hash). Sempre mantemos 1 cópia e listamos o resto."),
    DOWNLOADS_LARGE("Downloads grandes e antigos", false,
        "Arquivos de +50 MB baixados há +90 dias. Verifique se não precisa antes.")
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
