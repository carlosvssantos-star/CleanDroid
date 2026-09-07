package com.cleandroid.maintainer.core

import android.os.Build

/**
 * Centraliza branching por versão.
 * Estratégia multi-versão: API 21-35
 * - API 21-28: acesso livre a arquivos, sem scoped storage
 * - API 29: scoped storage opt-out via requestLegacyExternalStorage
 * - API 30-32: MANAGE_EXTERNAL_STORAGE (All files access)
 * - API 33+: READ_MEDIA_* granular + Photo Picker
 * - API 26+: Notification channels, Autofill, etc
 * - API 31+: PendingIntent mutability, SplashScreen
 */
object VersionCompat {
    val sdk: Int get() = Build.VERSION.SDK_INT

    val isLollipopPlus get() = sdk >= 21
    val isMarshmallowPlus get() = sdk >= Build.VERSION_CODES.M
    val isNougatPlus get() = sdk >= Build.VERSION_CODES.N
    val isOreoPlus get() = sdk >= Build.VERSION_CODES.O
    val isPiePlus get() = sdk >= 28
    val isQPlus get() = sdk >= 29
    val isRPlus get() = sdk >= 30
    val isSPlus get() = sdk >= 31
    val isTiramisuPlus get() = sdk >= 33
    val isUpsideDownCakePlus get() = sdk >= 34
    val isVanillaIceCreamPlus get() = sdk >= 35

    /** Scoped storage ativo? API 29+ (com opt-out na 29) e obrigatório na 30+ */
    fun isScopedStorageEnforced(): Boolean = sdk >= 30

    /** Precisa pedir MANAGE_EXTERNAL_STORAGE? */
    fun needsAllFilesAccess(): Boolean = sdk >= 30

    /** Usa permissões granulares de mídia? */
    fun usesGranularMedia(): Boolean = sdk >= 33

    fun label(): String = when {
        sdk >= 35 -> "Android 15+ (API $sdk)"
        sdk >= 34 -> "Android 14 (API $sdk)"
        sdk >= 33 -> "Android 13 (API $sdk)"
        sdk >= 31 -> "Android 12/12L (API $sdk)"
        sdk >= 30 -> "Android 11 (API $sdk)"
        sdk >= 29 -> "Android 10 (API $sdk)"
        sdk >= 28 -> "Android 9 (API $sdk)"
        sdk >= 26 -> "Android 8.0-8.1 (API $sdk)"
        sdk >= 24 -> "Android 7.0-7.1 (API $sdk)"
        sdk >= 23 -> "Android 6.0 (API $sdk)"
        else -> "Android 5.x (API $sdk)"
    }
}
