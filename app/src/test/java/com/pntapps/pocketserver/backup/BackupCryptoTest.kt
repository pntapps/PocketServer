package com.pntapps.pocketserver.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class BackupCryptoTest {
    private val passphrase = "correct horse battery staple"
    private val chunk = BackupCrypto.CHUNK_SIZE
    /** Size of a sealed full chunk in the file. */
    private val record = chunk + BackupCrypto.TAG_BYTES

    private fun data(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    private fun encrypt(plain: ByteArray, pass: String = passphrase): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, pass.toCharArray()).use { enc ->
            // Odd write sizes so chunk boundaries fall inside writes.
            var off = 0
            while (off < plain.size) {
                val n = minOf(77_777, plain.size - off)
                enc.write(plain, off, n)
                off += n
            }
        }
        return out.toByteArray()
    }

    private fun decrypt(sealed: ByteArray, pass: String = passphrase): ByteArray =
        BackupCrypto.decryptingStream(ByteArrayInputStream(sealed), pass.toCharArray()).use { it.readBytes() }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }

    @Test fun roundTripsAllSizes() {
        for (size in listOf(0, 1, chunk, chunk * 7 / 2)) {
            val plain = data(size)
            val sealed = encrypt(plain)
            val chunks = maxOf(1, (size + chunk - 1) / chunk)
            assertEquals("size $size", BackupCrypto.HEADER_SIZE + size + chunks * BackupCrypto.TAG_BYTES, sealed.size)
            assertArrayEquals("size $size", plain, decrypt(sealed))
        }
    }

    @Test fun sameInputEncryptsDifferently() {
        val plain = data(1000)
        assertFalse(encrypt(plain).contentEquals(encrypt(plain)))
    }

    @Test fun wrongPassphraseFails() {
        val sealed = encrypt(data(5000))
        assertThrows<WrongPassphraseException> { decrypt(sealed, "Correct horse battery staple") }
    }

    @Test fun flippedByteInMiddleChunkFails() {
        val sealed = encrypt(data(chunk * 7 / 2))
        sealed[BackupCrypto.HEADER_SIZE + record + record / 2] = (sealed[BackupCrypto.HEADER_SIZE + record + record / 2].toInt() xor 1).toByte()
        val e = assertThrows<DamagedBackupException> { decrypt(sealed) }
        assertTrue(e.message!!, "chunk 2" in e.message!!)
    }

    @Test fun flippedHeaderByteFails() {
        val sealed = encrypt(data(10))
        sealed[BackupCrypto.HEADER_SIZE - 20] = (sealed[BackupCrypto.HEADER_SIZE - 20].toInt() xor 1).toByte() // base nonce
        assertThrows<BackupCryptoException> { decrypt(sealed) }
    }

    @Test fun droppedLastChunkFails() {
        val sealed = encrypt(data(chunk * 7 / 2))
        val cut = sealed.copyOf(BackupCrypto.HEADER_SIZE + 3 * record)
        val e = assertThrows<DamagedBackupException> { decrypt(cut) }
        assertTrue(e.message!!, "truncated" in e.message!!)
        // Cut inside a chunk, and down to the bare header.
        assertThrows<DamagedBackupException> { decrypt(sealed.copyOf(sealed.size - 100)) }
        assertThrows<DamagedBackupException> { decrypt(sealed.copyOf(BackupCrypto.HEADER_SIZE)) }
        assertThrows<DamagedBackupException> { decrypt(sealed.copyOf(BackupCrypto.HEADER_SIZE - 1)) }
    }

    @Test fun appendedDataFails() {
        val sealed = encrypt(data(chunk))
        assertThrows<DamagedBackupException> { decrypt(sealed + ByteArray(1)) }
    }

    @Test fun reorderedChunksFail() {
        val sealed = encrypt(data(chunk * 7 / 2))
        val h = BackupCrypto.HEADER_SIZE
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, h + record, swapped, h, record)
        System.arraycopy(sealed, h, swapped, h + record, record)
        assertThrows<DamagedBackupException> { decrypt(swapped) }
    }

    @Test fun chunksFromAnotherBackupFail() {
        // Same passphrase, different salt and nonce: splicing a chunk in must not authenticate.
        val a = encrypt(data(chunk * 2 + 10))
        val b = encrypt(data(chunk * 2 + 10))
        val h = BackupCrypto.HEADER_SIZE
        System.arraycopy(b, h + record, a, h + record, record)
        assertThrows<DamagedBackupException> { decrypt(a) }
    }

    @Test fun detectsEncryption() {
        assertTrue(BackupCrypto.isEncrypted(encrypt(ByteArray(0))))
        assertTrue(BackupCrypto.isEncrypted("PSBK1".toByteArray()))
        assertFalse(BackupCrypto.isEncrypted("PSBK".toByteArray()))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(0)))
        // A plain .tar.gz starts with the gzip magic.
        val gz = ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(1) } }.toByteArray()
        assertFalse(BackupCrypto.isEncrypted(gz))
    }

    @Test fun decryptedBackupKeepsItsManifest() {
        val manifest = BackupManifest(createdAt = 1, appVersion = "0.1.0", apps = listOf("vaultwarden"), shared = false)
        val tar = ByteArrayOutputStream()
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(java.util.zip.GZIPOutputStream(tar)).use { t ->
            val body = BackupFormat.encode(manifest).toByteArray()
            t.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry(BackupFormat.MANIFEST_NAME).apply { size = body.size.toLong() })
            t.write(body)
            t.closeArchiveEntry()
        }
        val sealed = encrypt(tar.toByteArray())
        val read = BackupCrypto.decryptingStream(ByteArrayInputStream(sealed), passphrase.toCharArray()).use { BackupFormat.readManifest(it) }
        assertEquals(manifest, read)
        // Wrong passphrase surfaces as such through readManifest, not as "not a backup".
        assertThrows<WrongPassphraseException> {
            BackupCrypto.decryptingStream(ByteArrayInputStream(sealed), "nope nope".toCharArray()).use { BackupFormat.readManifest(it) }
        }
    }
}
