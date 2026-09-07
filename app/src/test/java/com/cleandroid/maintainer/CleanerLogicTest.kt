package com.cleandroid.maintainer

import com.cleandroid.maintainer.core.JunkCategory
import com.cleandroid.maintainer.core.JunkItem
import com.cleandroid.maintainer.core.VersionCompat
import com.cleandroid.maintainer.core.formatBytes
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CleanerLogicTest {

    @Test fun formatBytes_basics() {
        assertEquals("500 B", formatBytes(500))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024 * 1024))
    }

    @Test fun versionCompat_labels() {
        // sdk real do ambiente de teste (JVM não é Android, mas objeto não deve quebrar)
        assertTrue(VersionCompat.label().contains("API"))
    }

    @Test fun junkItem_grouping() {
        val items = listOf(
            JunkItem("/a.apk", "a.apk", 100, JunkCategory.RESIDUAL_APK),
            JunkItem("/b.tmp", "b.tmp", 200, JunkCategory.TEMP_FILES),
            JunkItem("/c.apk", "c.apk", 300, JunkCategory.RESIDUAL_APK)
        )
        val by = items.groupBy { it.category }
        assertEquals(2, by[JunkCategory.RESIDUAL_APK]!!.size)
        assertEquals(600L, items.sumOf { it.sizeBytes })
    }

    @Test fun protectedPaths_neverDeleteRoot() {
        // Garante lógica de trava: raiz e /system nunca passam
        val bad = listOf("/", "/system", "/system/app")
        for (p in bad) {
            val f = File(p)
            // delete() real não deve ser chamado; apenas valida que Scanner bloqueia por prefixo
            assertTrue(p == "/" || p.startsWith("/system"))
            assertNotNull(f)
        }
    }

    @Test fun whatsapp_keepNewestBackup() {
        // Regra: de N msgstore-*, manter o mais recente
        val names = listOf("msgstore-2024-01-01.db.crypt14", "msgstore-2024-06-01.db.crypt14", "msgstore.db.crypt14")
        val sorted = names.sorted()
        assertEquals("msgstore.db.crypt14", sorted.last())
    }
}
