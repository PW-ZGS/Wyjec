package pl.siren.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Alarm cryptography: ECDSA P-256 / SHA-256. Supported natively by the JDK and by Android,
 * so the same code signs on the server console and on controller phones.
 */
object AlarmCrypto {
    private const val SIGNATURE_ALG = "SHA256withECDSA"

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicKey(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALG).run {
            initSign(key)
            update(data)
            sign()
        }

    fun verify(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance(SIGNATURE_ALG).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
}

object Hashing {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
