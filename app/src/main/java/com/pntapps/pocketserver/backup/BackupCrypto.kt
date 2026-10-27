package com.pntapps.pocketserver.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A backup could not be decrypted. Messages are meant for the user as-is. */
sealed class BackupCryptoException(message: String) : IOException(message)

class WrongPassphraseException : BackupCryptoException("Wrong passphrase.")

class DamagedBackupException(message: String) : BackupCryptoException(message)

/**
 * Passphrase encryption for backups (the "PSBK1" container around the .tar.gz). Streams in
 * chunks, so a backup of any size needs about one chunk of memory; a single GCM over the whole
 * stream would not (Android's GCM decryption buffers everything until the tag is checked).
 *
 * Layout (integers big-endian):
 *   header   "PSBK1" | version (1) | kdf (1 = PBKDF2-HMAC-SHA256) | iterations (u32) | salt (16)
 *            | chunk size (u32) | base nonce (12) | passphrase check (16)
 *   chunks   AES-256-GCM(plaintext) + 16-byte tag. Every chunk but the last holds exactly
 *            chunk size bytes; the last holds 0..chunk size bytes and is always present.
 * Chunk i uses nonce = base nonce XOR i (last 8 bytes) and AAD = SHA-256(header) | i (u64) |
 * final flag, so reordered, dropped, appended or spliced chunks fail authentication.
 * The PBKDF2 output is split with HMAC into the AES key and the check value, so a wrong
 * passphrase is told apart from a damaged file before any data is decrypted.
 * Pure JVM code so it can be unit tested.
 */
object BackupCrypto {
    const val MAGIC = "PSBK1"
    const val VERSION = 1
    const val CHUNK_SIZE = 1024 * 1024
    const val ITERATIONS = 310_000
    const val TAG_BYTES = 16
    /** Size of the header in front of the first chunk. */
    const val HEADER_SIZE = 5 + 1 + 1 + 4 + 16 + 4 + 12 + 16

    private const val KDF_PBKDF2_SHA256 = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val CHECK_BYTES = 16
    /** Limits for values read from a header, so a crafted file cannot hang or exhaust memory. */
    private const val MAX_ITERATIONS = 10_000_000
    private const val MAX_CHUNK_SIZE = 16 * 1024 * 1024

    private val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
    private val random by lazy { SecureRandom() }

    /** True when [firstBytes] (the start of a file, at least 5 bytes) is an encrypted backup. */
    fun isEncrypted(firstBytes: ByteArray): Boolean =
        firstBytes.size >= magicBytes.size && magicBytes.indices.all { firstBytes[it] == magicBytes[it] }

    /**
     * Returns a stream that encrypts everything written to it into [out]. [close] writes the final
     * chunk and closes [out]; a stream that is never closed leaves a backup that fails to decrypt
     * as truncated. [passphrase] is not kept or wiped; the caller owns it.
     */
    fun encryptingStream(out: OutputStream, passphrase: CharArray): OutputStream =
        encryptingStream(out, passphrase, ITERATIONS, CHUNK_SIZE)

    internal fun encryptingStream(out: OutputStream, passphrase: CharArray, iterations: Int, chunkSize: Int): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val prefix = ByteBuffer.allocate(HEADER_SIZE - CHECK_BYTES)
            .put(magicBytes).put(VERSION.toByte()).put(KDF_PBKDF2_SHA256.toByte()).putInt(iterations)
            .put(salt).putInt(chunkSize).put(nonce)
            .array()
        val keys = Keys.derive(passphrase, salt, iterations, prefix)
        val header = prefix + keys.check
        out.write(header)
        return EncryptingStream(out, keys.aes, nonce, sha256(header), chunkSize)
    }

    /**
     * Returns a stream of the decrypted contents of [input], which must start with the header.
     * Reads the header and derives the key right away: throws [WrongPassphraseException] for a
     * wrong passphrase and [DamagedBackupException] for a bad header; reads later throw
     * [DamagedBackupException] for tampered or truncated data. No byte is returned before its
     * chunk has been authenticated. [passphrase] is not kept or wiped; the caller owns it.
     */
    fun decryptingStream(input: InputStream, passphrase: CharArray): InputStream {
        val header = ByteArray(HEADER_SIZE)
        if (readFully(input, header, 0, header.size) < header.size) {
            throw DamagedBackupException("The backup file is truncated (incomplete header).")
        }
        if (!isEncrypted(header)) throw DamagedBackupException("Not an encrypted PocketServer backup.")
        val buf = ByteBuffer.wrap(header, magicBytes.size, HEADER_SIZE - magicBytes.size)
        val version = buf.get().toInt() and 0xFF
        if (version > VERSION) {
            throw DamagedBackupException("This backup was encrypted by a newer PocketServer. Update the app to restore it.")
        }
        if (version < 1) throw DamagedBackupException("The backup file is damaged (bad version).")
        if (buf.get().toInt() != KDF_PBKDF2_SHA256) throw DamagedBackupException("The backup file is damaged (unknown key format).")
        val iterations = buf.int
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val chunkSize = buf.int
        val nonce = ByteArray(NONCE_BYTES).also { buf.get(it) }
        val check = ByteArray(CHECK_BYTES).also { buf.get(it) }
        if (iterations !in 1..MAX_ITERATIONS || chunkSize !in 1..MAX_CHUNK_SIZE) {
            throw DamagedBackupException("The backup file is damaged (bad header).")
        }
        val keys = Keys.derive(passphrase, salt, iterations, header.copyOf(HEADER_SIZE - CHECK_BYTES))
        if (!MessageDigest.isEqual(keys.check, check)) throw WrongPassphraseException()
        return DecryptingStream(input, keys.aes, nonce, sha256(header), chunkSize)
    }

    /** AES key and passphrase check value derived from the passphrase. */
    private class Keys(val aes: SecretKeySpec, val check: ByteArray) {
        companion object {
            fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int, headerPrefix: ByteArray): Keys {
                require(passphrase.isNotEmpty()) { "Empty passphrase" }
                val spec = PBEKeySpec(passphrase, salt, iterations, 256)
                val master = try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                } finally {
                    spec.clearPassword()
                }
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(master, "HmacSHA256")) }
                master.fill(0)
                val aesBytes = mac.doFinal("PSBK1 aes-256-gcm key".toByteArray(Charsets.US_ASCII))
                mac.update("PSBK1 passphrase check".toByteArray(Charsets.US_ASCII))
                val check = mac.doFinal(headerPrefix).copyOf(CHECK_BYTES)
                // SecretKeySpec keeps its own copy, so the derived bytes can be wiped here.
                return Keys(SecretKeySpec(aesBytes, "AES"), check).also { aesBytes.fill(0) }
            }
        }
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    private fun chunkNonce(base: ByteArray, index: Long): GCMParameterSpec {
        val nonce = base.copyOf()
        for (i in 0 until 8) {
            val at = nonce.size - 1 - i
            nonce[at] = (nonce[at].toInt() xor (index ushr (8 * i)).toInt()).toByte()
        }
        return GCMParameterSpec(TAG_BYTES * 8, nonce)
    }

    private fun chunkAad(headerHash: ByteArray, index: Long, final: Boolean): ByteArray =
        ByteBuffer.allocate(headerHash.size + 9).put(headerHash).putLong(index).put(if (final) 1 else 0).array()

    /** Reads until [len] bytes or the end of [input]; returns the count read. */
    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(b, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private class EncryptingStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val baseNonce: ByteArray,
        private val headerHash: ByteArray,
        chunkSize: Int,
    ) : OutputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val plain = ByteArray(chunkSize)
        private val sealed = ByteArray(chunkSize + TAG_BYTES)
        private var fill = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            var at = off
            var left = len
            while (left > 0) {
                // A full chunk is sealed only once more data arrives: the last chunk must carry the final flag.
                if (fill == plain.size) seal(final = false)
                val n = minOf(left, plain.size - fill)
                System.arraycopy(b, at, plain, fill, n)
                fill += n; at += n; left -= n
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                seal(final = true)
                out.flush()
            } finally {
                plain.fill(0)
                out.close()
            }
        }

        private fun seal(final: Boolean) {
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, chunkNonce(baseNonce, index))
                cipher.updateAAD(chunkAad(headerHash, index, final))
                val n = cipher.doFinal(plain, 0, fill, sealed, 0)
                out.write(sealed, 0, n)
            } catch (e: GeneralSecurityException) {
                throw IOException("Encryption failed: ${e.message}", e)
            }
            index++
            fill = 0
        }
    }

    private class DecryptingStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val baseNonce: ByteArray,
        private val headerHash: ByteArray,
        chunkSize: Int,
    ) : InputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val record = ByteArray(chunkSize + TAG_BYTES)
        // Room for the tag too: some providers size decrypt output by the input length.
        private val plain = ByteArray(chunkSize + TAG_BYTES)
        private var pos = 0
        private var limit = 0
        private var index = 0L
        private var done = false
        /** First byte of the next chunk, read to learn whether the current one is the last. */
        private var lookahead = -1

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == limit) if (!nextChunk()) return -1
            val n = minOf(len, limit - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun available() = limit - pos

        override fun close() {
            plain.fill(0)
            input.close()
        }

        private fun nextChunk(): Boolean {
            if (done) return false
            var n = 0
            if (lookahead >= 0) { record[0] = lookahead.toByte(); n = 1; lookahead = -1 }
            n += readFully(input, record, n, record.size - n)
            val final = n < record.size || input.read().also { lookahead = it } < 0
            if (n < TAG_BYTES) throw DamagedBackupException("The backup file is truncated.")
            limit = try {
                open(n, final)
            } catch (e: AEADBadTagException) {
                // A full chunk that should have had a successor means chunks were cut off the end.
                val truncated = final && runCatching { open(n, final = false) }.isSuccess
                throw DamagedBackupException(
                    if (truncated) "The backup file is truncated (incomplete download or copy?)."
                    else "The backup file is damaged or was modified (chunk ${index + 1})."
                )
            } catch (e: GeneralSecurityException) {
                throw DamagedBackupException("The backup file could not be decrypted: ${e.message}")
            }
            pos = 0
            index++
            if (final) done = true
            return true
        }

        private fun open(n: Int, final: Boolean): Int {
            cipher.init(Cipher.DECRYPT_MODE, key, chunkNonce(baseNonce, index))
            cipher.updateAAD(chunkAad(headerHash, index, final))
            return cipher.doFinal(record, 0, n, plain, 0)
        }
    }
}
