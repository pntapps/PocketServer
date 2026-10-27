package com.pntapps.pocketserver.backup

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

class BackupFormatTest {
    private val sample = BackupManifest(createdAt = 1_700_000_000_000, appVersion = "0.1.0", apps = listOf("vaultwarden", "jellyfin"), shared = true)

    private fun archive(vararg entries: Pair<String, String>, gzip: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        val tarTarget = if (gzip) GZIPOutputStream(bytes) else bytes
        TarArchiveOutputStream(tarTarget).use { tar ->
            for ((name, body) in entries) {
                val data = body.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("expected InvalidBackupException") } catch (_: InvalidBackupException) {}
    }

    @Test fun manifestRoundTrips() {
        assertEquals(sample, BackupFormat.decode(BackupFormat.encode(sample)))
        assertTrue(BackupFormat.encode(sample).contains("\"format\": \"pocketserver-backup\""))
    }

    @Test fun ignoresUnknownFields() {
        val text = """{"format":"pocketserver-backup","version":1,"created":5,"appVersion":"9","apps":["syncthing"],"shared":false,"extra":42}"""
        assertEquals(listOf("syncthing"), BackupFormat.decode(text).apps)
    }

    @Test fun rejectsForeignOrDamagedManifests() {
        assertInvalid { BackupFormat.decode("not json") }
        assertInvalid { BackupFormat.decode("""{"format":"other","version":1,"created":1,"appVersion":"x","apps":["a"],"shared":false}""") }
        assertInvalid { BackupFormat.validate(sample.copy(version = BackupFormat.VERSION + 1)) }
        assertInvalid { BackupFormat.validate(sample.copy(version = 0)) }
        assertInvalid { BackupFormat.validate(sample.copy(apps = listOf("../etc"))) }
        assertInvalid { BackupFormat.validate(sample.copy(apps = listOf("a b"))) }
        assertInvalid { BackupFormat.validate(sample.copy(apps = listOf("x", "x"))) }
        assertInvalid { BackupFormat.validate(sample.copy(apps = emptyList(), shared = false)) }
        BackupFormat.validate(sample.copy(apps = emptyList(), shared = true))
    }

    @Test fun readsManifestFromFirstEntry() {
        val data = archive(BackupFormat.MANIFEST_NAME to BackupFormat.encode(sample), "apps/vaultwarden/x" to "secret")
        assertEquals(sample, BackupFormat.readManifest(ByteArrayInputStream(data)))
    }

    @Test fun rejectsArchivesWithoutLeadingManifest() {
        assertInvalid { BackupFormat.readManifest(ByteArrayInputStream(archive("apps/a/x" to "1", BackupFormat.MANIFEST_NAME to BackupFormat.encode(sample)))) }
        assertInvalid { BackupFormat.readManifest(ByteArrayInputStream(archive(BackupFormat.MANIFEST_NAME to BackupFormat.encode(sample), gzip = false))) }
        assertInvalid { BackupFormat.readManifest(ByteArrayInputStream(ByteArray(0))) }
        assertInvalid { BackupFormat.readManifest(ByteArrayInputStream("hello world, not gzip".toByteArray())) }
        assertInvalid { BackupFormat.readManifest(ByteArrayInputStream(archive())) }
        val full = archive(BackupFormat.MANIFEST_NAME to BackupFormat.encode(sample))
        assertInvalid { BackupFormat.readManifest(ByteArrayInputStream(full.copyOf(30))) }
    }

    @Test fun defaultFileName() {
        assertEquals("pocketserver-20260305.tar.gz", BackupFormat.defaultFileName(LocalDate.of(2026, 3, 5)))
        assertEquals("pocketserver-20260305.tar.gz.enc", BackupFormat.defaultFileName(LocalDate.of(2026, 3, 5), encrypted = true))
    }

    @Test fun tarArguments() {
        val create = BackupFormat.createArgs("/tmp/m", listOf("vaultwarden"), includeShared = true)
        assertEquals(
            listOf(
                "tar", "-czf", "-", "--anchored", "--no-wildcards-match-slash",
                "--exclude=apps/*/cache", "--exclude=apps/*/log", "--exclude=apps/*/logs",
                "-C", "/tmp/m", BackupFormat.MANIFEST_NAME, "-C", "/opt/pocketserver", "apps/vaultwarden", "-C", "/", "shared",
            ),
            create,
        )
        val sharedOnly = BackupFormat.createArgs("/tmp/m", emptyList(), includeShared = true)
        assertFalse("/opt/pocketserver" in sharedOnly)
        assertEquals(
            listOf("tar", "-xzf", "-", "-C", "/s", "--no-same-owner", BackupFormat.MANIFEST_NAME, "apps/jellyfin"),
            BackupFormat.extractArgs("/s", listOf("jellyfin"), includeShared = false),
        )
    }

    @Test fun formatsBytes() {
        assertEquals("512 B", BackupFormat.formatBytes(512))
        assertEquals("2 KB", BackupFormat.formatBytes(2048))
        assertEquals("1.5 MB", BackupFormat.formatBytes(1_572_864))
        assertEquals("2.00 GB", BackupFormat.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun certificateAuthorityTravelsWithApps() {
        val create = BackupFormat.createArgs("/tmp/m", listOf("vaultwarden"), includeShared = false, includeCa = true)
        assertTrue(create.indexOf("ca") > create.indexOf("apps/vaultwarden"))
        val extract = BackupFormat.extractArgs("/s", listOf("vaultwarden"), includeShared = false, includeCa = true)
        assertTrue("ca" in extract)
        assertFalse("ca" in BackupFormat.extractArgs("/s", listOf("vaultwarden"), includeShared = false))
    }

    @Test fun backupsFromBeforeTheCaFieldStillDecode() {
        val old = BackupFormat.encode(BackupManifest(createdAt = 1, appVersion = "0.1.0", apps = listOf("memos"), shared = false))
            .replace(Regex(""",?\s*"ca"\s*:\s*false"""), "")
        assertFalse(BackupFormat.decode(old).ca)
    }
}
