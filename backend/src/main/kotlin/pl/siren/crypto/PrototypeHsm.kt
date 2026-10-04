package pl.siren.crypto

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * Stand-in for a hardware security module. Production keeps PSKs and alarm private keys in an HSM
 * and stores only references (`*_hsm_ref`) in the operational DB; the prototype keeps them in a table.
 */
@Component
class PrototypeHsm(private val jdbc: NamedParameterJdbcTemplate) {

    fun store(ref: String, secret: ByteArray) {
        jdbc.update(
            "INSERT INTO prototype_hsm_secret (ref, secret) VALUES (:ref, :secret) ON CONFLICT (ref) DO UPDATE SET secret = :secret",
            mapOf("ref" to ref, "secret" to secret),
        )
    }

    fun load(ref: String): ByteArray? =
        jdbc.query("SELECT secret FROM prototype_hsm_secret WHERE ref = :ref", mapOf("ref" to ref)) { rs, _ ->
            rs.getBytes("secret")
        }.firstOrNull()
}
