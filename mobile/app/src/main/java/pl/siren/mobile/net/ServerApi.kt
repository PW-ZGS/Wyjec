package pl.siren.mobile.net

import org.json.JSONObject
import pl.siren.mobile.crypto.Crypto
import pl.siren.mobile.data.Config
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String) : IOException(message) {
    /** 5xx: the server or the proxy in front of it is down — retry later, keep queued data. */
    val serverDown get() = code >= 500
}

/**
 * Server–App channel. Each request is authenticated with the device's individually distributed PSK:
 * HMAC-SHA256(psk, METHOD \n PATH?QUERY \n TIMESTAMP \n hex(SHA-256(body))).
 */
class ServerApi(private val config: Config) {

    fun sync(): JSONObject = call("GET", "/api/device/sync", null)

    fun alarms(since: Long): JSONObject = call("GET", "/api/device/alarms?since=$since", null)

    fun submitAlarm(payloadB64: String, signatureB64: String, bearer: String, hop: Int): JSONObject =
        call("POST", "/api/device/alarms", JSONObject()
            .put("payload", payloadB64).put("signature", signatureB64).put("bearer", bearer).put("hopCount", hop))

    fun status(batch: JSONObject): JSONObject = call("POST", "/api/device/status", batch)

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val url = URL(config.serverUrl + path)
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        val timestamp = System.currentTimeMillis().toString()
        val canonical = "$method\n${url.path}${url.query?.let { "?$it" } ?: ""}\n$timestamp\n${Crypto.hex(Crypto.sha256(bytes))}"
        val signature = Crypto.hex(Crypto.hmacSha256(config.psk.toByteArray(), canonical.toByteArray()))

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.setRequestProperty("X-Siren-Device", config.deviceId)
            conn.setRequestProperty("X-Siren-Timestamp", timestamp)
            conn.setRequestProperty("X-Siren-Signature", signature)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null }
                throw HttpException(code, reason ?: "HTTP $code")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
