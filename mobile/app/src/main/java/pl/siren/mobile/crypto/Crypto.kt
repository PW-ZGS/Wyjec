package pl.siren.mobile.crypto

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Alarm signatures (ECDSA P-256 / SHA-256) and Server–App request MACs — same scheme as the backend. */
object Crypto {
    fun verify(publicKeySpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    fun sign(privateKeyPkcs8: ByteArray, data: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
