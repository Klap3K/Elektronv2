package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Narzędzia kryptograficzne dla protokołu VULCAN UONET+ / eduVULCAN (Hebe Mobile API).
 * Zarządza generowaniem kluczy RSA, certyfikatu X.509 v3 oraz podpisywaniem żądań HTTP.
 */
object VulcanCrypto {

    data class KeyCertificatePair(
        val keyPair: KeyPair,
        val certificatePem: String,
        val certificateThumbprintHex: String,
        val privateKeyPkcs8Base64: String
    )

    /**
     * Generuje nową parę kluczy RSA (2048-bit) oraz samopodpisany certyfikat X.509 v3.
     */
    fun generateKeyAndCertificate(): KeyCertificatePair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val certDer = generateX509CertificateDer(keyPair)
        val certPem = toPem("CERTIFICATE", certDer)
        val thumbprint = sha1Hex(certDer)
        val privateKeyPkcs8 = Base64.getEncoder().encodeToString(keyPair.private.encoded)

        return KeyCertificatePair(
            keyPair = keyPair,
            certificatePem = certPem,
            certificateThumbprintHex = thumbprint,
            privateKeyPkcs8Base64 = privateKeyPkcs8
        )
    }

    /**
     * Odtwarza klucz prywatny RSA z zapisanego ciągu Base64 (PKCS#8).
     */
    fun parsePrivateKey(base64Pkcs8: String): PrivateKey {
        val decoded = Base64.getDecoder().decode(base64Pkcs8.trim())
        val spec = PKCS8EncodedKeySpec(decoded)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(spec)
    }

    /**
     * Podpisuje kanoniczny ciąg żądania HTTP algorytmem SHA256withRSA.
     */
    fun sign(privateKey: PrivateKey, content: String): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(content.toByteArray(Charsets.UTF_8))
        val signature = signer.sign()
        return Base64.getEncoder().encodeToString(signature)
    }

    /**
     * Oblicza skrót SHA-256 dla treści żądania (body).
     */
    fun sha256Digest(content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(content.toByteArray(Charsets.UTF_8))
        return "SHA-256=" + Base64.getEncoder().encodeToString(hash)
    }

    /**
     * Oblicza odcisk palca SHA-1 w formacie szesnastkowym (uppercase).
     */
    fun sha1Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(data)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    /**
     * Koduje dane binarne do formatu PEM.
     */
    fun toPem(type: String, data: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(data)
        val chunked = base64.chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$chunked\n-----END $type-----"
    }

    /**
     * Generuje poprawny certyfikat ASN.1 DER X.509 v3 samopodpisany przez klucz prywatny.
     */
    fun generateX509CertificateDer(keyPair: KeyPair): ByteArray {
        val random = SecureRandom()
        val serialBytes = ByteArray(8).apply { random.nextBytes(this) }
        serialBytes[0] = (serialBytes[0].toInt() and 0x7F).toByte() // dodatnia liczba całkowita

        // Format dat UTCTime: YYMMDDHHMMSSZ
        val utcFormatter = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
        val notBefore = utcFormatter.format(Instant.now().minusSeconds(86400))
        val notAfter = utcFormatter.format(Instant.now().plusSeconds(86400L * 365 * 10)) // 10 lat

        // 1. TBSCertificate
        val tbsOut = ByteArrayOutputStream()

        // Version: [0] EXPLICIT INTEGER (2) => v3
        val version = derExplicit(0, derInteger(byteArrayOf(0x02)))
        tbsOut.write(version)

        // Serial Number
        tbsOut.write(derInteger(serialBytes))

        // Signature Algorithm (SHA256withRSA: 1.2.840.113549.1.1.11)
        val sha256RsaOid = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
        val sigAlg = derSequence(derOid(sha256RsaOid) + derNull())
        tbsOut.write(sigAlg)

        // Issuer: CN=eLektron
        val cnOid = byteArrayOf(0x55, 0x04, 0x03) // 2.5.4.3
        val issuerName = derSequence(
            derSet(
                derSequence(
                    derOid(cnOid) + derUtf8String("eLektron")
                )
            )
        )
        tbsOut.write(issuerName)

        // Validity: notBefore, notAfter
        val validity = derSequence(
            derUtcTime(notBefore) + derUtcTime(notAfter)
        )
        tbsOut.write(validity)

        // Subject: taki sam jak Issuer (self-signed)
        tbsOut.write(issuerName)

        // SubjectPublicKeyInfo (w RSAPublicKey.encoded jest już gotowy DER X.509 SubjectPublicKeyInfo)
        tbsOut.write(keyPair.public.encoded)

        val tbsDer = derSequence(tbsOut.toByteArray())

        // 2. Podpisanie TBSCertificate kluczem prywatnym
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsDer)
        val signatureValue = signer.sign()

        // 3. Złożenie kompletnego certyfikatu X.509
        val certOut = ByteArrayOutputStream()
        certOut.write(tbsDer)
        certOut.write(sigAlg)
        certOut.write(derBitString(signatureValue))

        return derSequence(certOut.toByteArray())
    }

    // --- Narzędzia ASN.1 DER ---

    private fun derSequence(content: ByteArray): ByteArray =
        byteArrayOf(0x30.toByte()) + encodeDerLength(content.size) + content

    private fun derSet(content: ByteArray): ByteArray =
        byteArrayOf(0x31.toByte()) + encodeDerLength(content.size) + content

    private fun derExplicit(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf((0xA0 or tag).toByte()) + encodeDerLength(content.size) + content

    private fun derInteger(value: ByteArray): ByteArray {
        val normalized = if (value.isNotEmpty() && (value[0].toInt() and 0x80) != 0) {
            byteArrayOf(0x00) + value
        } else {
            value
        }
        return byteArrayOf(0x02) + encodeDerLength(normalized.size) + normalized
    }

    private fun derOid(oidBytes: ByteArray): ByteArray =
        byteArrayOf(0x06) + encodeDerLength(oidBytes.size) + oidBytes

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derUtf8String(str: String): ByteArray {
        val bytes = str.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x0C) + encodeDerLength(bytes.size) + bytes
    }

    private fun derUtcTime(timeStr: String): ByteArray {
        val bytes = timeStr.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17) + encodeDerLength(bytes.size) + bytes
    }

    private fun derBitString(data: ByteArray): ByteArray {
        // Pierwszy bajt to liczba nieużywanych bitów (0x00)
        val content = byteArrayOf(0x00) + data
        return byteArrayOf(0x03) + encodeDerLength(content.size) + content
    }

    private fun encodeDerLength(len: Int): ByteArray {
        return when {
            len < 128 -> byteArrayOf(len.toByte())
            len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        }
    }
}
