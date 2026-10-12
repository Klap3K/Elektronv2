package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.zse.bydgoszcz.elektron.domain.model.VulcanRegistrationInput
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class VulcanCryptoTest {

    @Test
    fun `generateKeyAndCertificate creates valid X509 certificate parsable by Java CertificateFactory`() {
        val pair = VulcanCrypto.generateKeyAndCertificate()

        assertNotNull(pair.keyPair.public)
        assertNotNull(pair.keyPair.private)
        assertTrue(pair.certificatePem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(pair.certificatePem.endsWith("-----END CERTIFICATE-----"))
        assertTrue(pair.certificateThumbprintHex.length == 40) // SHA-1 is 20 bytes = 40 hex chars

        // Sprawdzamy czy CertificateFactory jest w stanie sparsować certyfikat z bajtów PEM
        val factory = CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertificate(ByteArrayInputStream(pair.certificatePem.toByteArray())) as X509Certificate

        assertNotNull(cert)
        assertEquals("CN=eLektron", cert.subjectX500Principal.name)
        assertEquals("CN=eLektron", cert.issuerX500Principal.name)
        cert.verify(pair.keyPair.public) // Samopodpisany - weryfikacja kluczem publicznym
    }

    @Test
    fun `sign creates signature verifiable by public key`() {
        val pair = VulcanCrypto.generateKeyAndCertificate()
        val textToSign = "api/mobile/exam/byPupil\n\nTue, 12 Oct 2026 12:00:00 GMT"

        val privateKey = VulcanCrypto.parsePrivateKey(pair.privateKeyPkcs8Base64)
        val signatureBase64 = VulcanCrypto.sign(privateKey, textToSign)
        val signatureBytes = java.util.Base64.getDecoder().decode(signatureBase64)

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pair.keyPair.public)
        verifier.update(textToSign.toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(signatureBytes))
    }

    @Test
    fun `parseOrNull parses JSON and delimited registration inputs`() {
        val jsonInput = """{"Token":"C123456","Symbol":"bydgoszcz","Pin":"654321"}"""
        val parsedJson = VulcanRegistrationInput.parseOrNull(jsonInput)
        assertNotNull(parsedJson)
        assertEquals("C123456", parsedJson?.token)
        assertEquals("bydgoszcz", parsedJson?.symbol)
        assertEquals("654321", parsedJson?.pin)

        val delimitedInput = "FK123456#bydgoszcz#112233"
        val parsedDelimited = VulcanRegistrationInput.parseOrNull(delimitedInput)
        assertNotNull(parsedDelimited)
        assertEquals("FK123456", parsedDelimited?.token)
        assertEquals("bydgoszcz", parsedDelimited?.symbol)
        assertEquals("112233", parsedDelimited?.pin)
    }
}
