package dev.omalauncher.linux

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.Date
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * TEST-ONLY key material: generated per test run, never packaged. The production trust anchor
 * is assets/archlinuxarm-build-system.gpg and is only ever used to verify.
 */
class TestSigner(
    bits: Int = 2048,
) {
    val pair: PGPKeyPair =
        RSAKeyPairGenerator().run {
            init(RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), SecureRandom(), bits, 80))
            BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, generateKeyPair(), Date(System.currentTimeMillis() - 86_400_000L))
        }

    /** The public key ring, as the app's asset would hold it. */
    val publicRing: ByteArray =
        PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pair,
            "Test signer <test@example.invalid>",
            BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
            null,
            null,
            BcPGPContentSignerBuilder(pair.publicKey.algorithm, HashAlgorithmTags.SHA256),
            null,
        ).generatePublicKeyRing().encoded

    val fingerprint: String = Hex.toHexString(pair.publicKey.fingerprint).uppercase()

    fun verifier() = RootfsVerifier(publicRing, fingerprint)

    fun sign(
        data: ByteArray,
        hash: Int = HashAlgorithmTags.SHA512,
        type: Int = PGPSignature.BINARY_DOCUMENT,
        /** Claim another key's ID as the issuer, as a forger would. */
        claimIssuer: Long? = null,
    ): ByteArray {
        val gen = PGPSignatureGenerator(BcPGPContentSignerBuilder(pair.publicKey.algorithm, hash), pair.publicKey)
        gen.init(type, pair.privateKey)
        if (claimIssuer != null) {
            gen.setUnhashedSubpackets(PGPSignatureSubpacketGenerator().apply { setIssuerKeyID(false, claimIssuer) }.generate())
        }
        gen.update(data)
        return gen.generate().encoded
    }

    companion object {
        /** Key generation is slow; tests share these. */
        val trusted by lazy { TestSigner() }
        val other by lazy { TestSigner() }
    }
}

/** Builds tar.gz fixtures; names are written exactly as given (absolute and ".." included). */
class TarFixture {
    private val bytes = ByteArrayOutputStream()
    private val tar =
        TarArchiveOutputStream(bytes).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }

    fun dir(
        name: String,
        mode: Int = 0x1ed,
    ) = add(TarArchiveEntry(name, TarConstants.LF_DIR, true).apply { this.mode = mode })

    fun file(
        name: String,
        content: String = name,
        mode: Int = 0x1a4,
    ) = apply {
        val data = content.toByteArray()
        tar.putArchiveEntry(
            TarArchiveEntry(name, true).apply {
                this.mode = mode
                size = data.size.toLong()
            },
        )
        tar.write(data)
        tar.closeArchiveEntry()
    }

    fun symlink(
        name: String,
        target: String,
    ) = add(
        TarArchiveEntry(name, TarConstants.LF_SYMLINK, true).apply {
            linkName = target
            mode = 0x1ff
        },
    )

    fun hardlink(
        name: String,
        target: String,
    ) = add(TarArchiveEntry(name, TarConstants.LF_LINK, true).apply { linkName = target })

    fun special(
        name: String,
        flag: Byte,
    ) = add(TarArchiveEntry(name, flag, true))

    /** An entry whose header claims [size] bytes, followed by [size] bytes of data. */
    fun sized(
        name: String,
        size: Long,
    ) = apply {
        tar.putArchiveEntry(TarArchiveEntry(name, true).apply { this.size = size })
        val chunk = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            tar.write(chunk, 0, n)
            left -= n
        }
        tar.closeArchiveEntry()
    }

    private fun add(entry: TarArchiveEntry) =
        apply {
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
        }

    /** The tar stream, finished with its end-of-archive records. */
    fun tar(): ByteArray {
        tar.finish()
        tar.close()
        return bytes.toByteArray()
    }

    fun gz(): ByteArray = gzip(tar())

    companion object {
        /** The parts of Arch Linux ARM's layout the extractor requires, in upstream's order (links before their targets). */
        fun minimalRootfs() =
            TarFixture()
                .dir("./")
                .symlink("./bin", "usr/bin")
                .dir("./etc/")
                .file("./etc/pacman.conf", "[options]\n")
                .dir("./etc/pacman.d/")
                .symlink("./lib", "usr/lib")
                .dir("./root/", 0x1e8) // 0750
                .symlink("./sbin", "usr/bin")
                .dir("./usr/")
                .dir("./usr/bin/")
                .file("./usr/bin/bash", "#!bash", 0x1ed)
                .file("./usr/bin/env", "env", 0x1ed)
                .file("./usr/bin/pacman", "pacman", 0x1ed)
                .symlink("./usr/bin/sh", "bash")
                .dir("./usr/lib/")
                .file("./usr/lib/ld-linux-aarch64.so.1", "ld", 0x1ed)
                .symlink("./usr/sbin", "bin")
    }
}

fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

/** One 512-byte ustar header with raw [name] bytes, for names commons-compress wouldn't write. */
fun rawTarHeader(
    name: ByteArray,
    type: Char = '0',
    size: Long = 0,
    linkName: ByteArray = ByteArray(0),
    /** The 12 raw bytes of the size field instead of [size] in octal: base-256 or malformed. */
    sizeField: ByteArray? = null,
): ByteArray {
    val h = ByteArray(512)
    name.copyInto(h, 0, 0, minOf(name.size, 100))
    fun octal(
        value: Long,
        offset: Int,
        length: Int,
    ) = String.format("%0${length - 1}o", value).toByteArray().copyInto(h, offset)
    octal(0x1a4, 100, 8)
    octal(0, 108, 8)
    octal(0, 116, 8)
    if (sizeField != null) sizeField.copyInto(h, 124) else octal(size, 124, 12)
    octal(0, 136, 12)
    h[156] = type.code.toByte()
    linkName.copyInto(h, 157, 0, minOf(linkName.size, 100))
    "ustar\u000000".toByteArray().copyInto(h, 257)
    for (i in 148 until 156) h[i] = ' '.code.toByte()
    val sum = h.sumOf { it.toInt() and 0xff }
    String.format("%06o", sum).toByteArray().copyInto(h, 148)
    h[154] = 0
    h[155] = ' '.code.toByte()
    return h
}

/** A PAX header body: "<length> <key>=<value>\n" records, the length counting itself. */
fun pax(vararg records: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((key, value) in records) {
        val rest = " $key=$value\n".toByteArray().size
        var length = rest + 1
        while ("$length".length + rest != length) length = "$length".length + rest
        out.write("$length $key=$value\n".toByteArray())
    }
    return out.toByteArray()
}

/** A raw tar stream: entries with data padded to 512 bytes, then the end-of-archive records. */
fun rawTar(
    vararg entries: Pair<ByteArray, ByteArray>,
    end: Boolean = true,
): ByteArray {
    val out = ByteArrayOutputStream()
    for ((header, data) in entries) {
        out.write(header)
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }
    if (end) out.write(ByteArray(1024))
    return out.toByteArray()
}

/** Signs [data] with [signer], writes it to [file], and returns the verified handle. */
fun verified(
    file: File,
    data: ByteArray,
    signer: TestSigner = TestSigner.trusted,
): VerifiedRootfs {
    file.writeBytes(data)
    return signer.verifier().verify(file, signer.sign(data))
}

/**
 * A tiny HTTP/1.1 server on 127.0.0.1 with full control over status, headers and how much of
 * the body is sent, so tests can cut transfers short. Every connection is closed after a reply.
 */
class RawHttpServer(
    private val handler: (Request) -> Response,
) : AutoCloseable {
    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    ) {
        fun header(name: String) = headers[name.lowercase()]
    }

    class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        /** Send Content-Length for the whole body (or this many, if set). */
        val contentLength: Long? = body.size.toLong(),
        /** Send only this many body bytes, then close. */
        val sendOnly: Int? = null,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())
    val base = "http://127.0.0.1:${socket.localPort}"

    private val acceptor =
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(client) }
            }
        }

    private fun serve(client: Socket) {
        client.use { c ->
            val input = c.getInputStream()
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            val request = Request(parts[0], parts[1], headers)
            requests += request
            val response = handler(request)
            val out = c.getOutputStream()
            val head = StringBuilder("HTTP/1.1 ${response.status} X\r\nConnection: close\r\n")
            response.contentLength?.let { head.append("Content-Length: $it\r\n") }
            response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray())
            if (request.method != "HEAD") writeBody(out, response)
            out.flush()
        }
    }

    private fun writeBody(
        out: OutputStream,
        response: Response,
    ) {
        val n = response.sendOnly ?: response.body.size
        out.write(response.body, 0, minOf(n, response.body.size))
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    override fun close() {
        socket.close()
        acceptor.join(1000)
    }
}
