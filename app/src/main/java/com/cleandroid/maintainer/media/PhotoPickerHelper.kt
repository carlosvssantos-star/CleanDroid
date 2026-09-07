package com.cleandroid.maintainer.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Photo Picker (API 33+) + MediaStore para duplicados de mídia
 * sem exigir MANAGE_EXTERNAL_STORAGE.
 * - API 33+: usa Photo Picker (sem permissão) para o usuário escolher mídias
 * - API 29-32: MediaStore com READ_EXTERNAL_STORAGE
 * - API 21-28: acesso direto (JunkScanner já cobre)
 */
object PhotoPickerHelper {

    /** Registra picker na Activity; chame no onCreate. Retorna launcher via callback. */
    fun register(
        activity: ComponentActivity,
        onPicked: (List<Uri>) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest> {
        return activity.registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(20), onPicked
        )
    }

    fun pickImages(launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>) {
        try {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Exception) {}
    }

    /** Lista mídias via MediaStore (para detector de duplicados de fotos sem All Files) */
    data class MediaRow(val uri: Uri, val name: String, val size: Long)

    fun listImagesViaMediaStore(ctx: Context, limit: Int = 2000): List<MediaRow> {
        val out = mutableListOf<MediaRow>()
        try {
            val coll = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION") MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            ctx.contentResolver.query(
                coll,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                ),
                null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    val uri = Uri.withAppendedPath(coll, id.toString())
                    out += MediaRow(uri, c.getString(nameCol) ?: "img", c.getLong(sizeCol))
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
