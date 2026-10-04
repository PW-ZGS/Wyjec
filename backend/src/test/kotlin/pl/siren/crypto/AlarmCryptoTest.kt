package pl.siren.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmCryptoTest {

    @Test
    fun `signature verifies only for the exact payload and key`() {
        val pair = AlarmCrypto.generateKeyPair()
        val other = AlarmCrypto.generateKeyPair()
        val payload = """{"type":"RAISE","code":"DRONE_STRIKE"}""".toByteArray()
        val signature = AlarmCrypto.sign(pair.private, payload)

        assertTrue(AlarmCrypto.verify(pair.public, payload, signature))
        assertFalse(AlarmCrypto.verify(pair.public, payload.copyOf().also { it[2] = 'x'.code.toByte() }, signature))
        assertFalse(AlarmCrypto.verify(other.public, payload, signature))
        assertFalse(AlarmCrypto.verify(pair.public, payload, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `keys survive encoding round trip as stored in DB and HSM`() {
        val pair = AlarmCrypto.generateKeyPair()
        val restoredPublic = AlarmCrypto.publicKey(pair.public.encoded)
        val restoredPrivate = AlarmCrypto.privateKey(pair.private.encoded)
        val payload = "cancel".toByteArray()
        assertTrue(AlarmCrypto.verify(restoredPublic, payload, AlarmCrypto.sign(restoredPrivate, payload)))
    }

    @Test
    fun `hmac matches the device request signing scheme`() {
        val body = """{"receptions":[]}""".toByteArray()
        val canonical = "POST\n/api/device/status\n1700000000000\n${Hashing.hex(Hashing.sha256(body))}"
        val mac = Hashing.hex(Hashing.hmacSha256("demo-psk-tomasz".toByteArray(), canonical.toByteArray()))
        // Reference value computed independently with Python's hmac module.
        assertEquals("875475a2423801242600faae8e3520c0e4b07e954c453f915cb0d46b3c34ac1b", mac)
    }
}
