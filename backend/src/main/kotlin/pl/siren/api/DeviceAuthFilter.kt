package pl.siren.api

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.siren.config.SirenProperties
import pl.siren.crypto.Hashing
import pl.siren.identity.IdentityRepository
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Server–App channel authentication with individually distributed symmetric credentials.
 * Every device request carries:
 *   X-Siren-Device    device id
 *   X-Siren-Timestamp epoch millis
 *   X-Siren-Signature hex HMAC-SHA256(psk, METHOD \n PATH?QUERY \n TIMESTAMP \n hex(SHA-256(body)))
 */
@Component
class DeviceAuthFilter(
    private val identity: IdentityRepository,
    private val props: SirenProperties,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/device/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val body = request.inputStream.readAllBytes()
        val deviceId = request.getHeader("X-Siren-Device")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val timestamp = request.getHeader("X-Siren-Timestamp")?.toLongOrNull()
        val signature = request.getHeader("X-Siren-Signature")
        if (deviceId == null || timestamp == null || signature == null) return deny(response, "missing device authentication headers")

        val skew = Duration.between(Instant.ofEpochMilli(timestamp), Instant.now()).abs()
        if (skew > props.maxClockSkew) return deny(response, "request timestamp outside allowed clock skew")

        val device = identity.findDevice(deviceId) ?: return deny(response, "unknown device")
        if (device.status != "ACTIVE") return deny(response, "device is ${device.status}")
        val psk = identity.activePsk(deviceId) ?: return deny(response, "no valid credential")

        val path = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        val canonical = "${request.method}\n$path\n$timestamp\n${Hashing.hex(Hashing.sha256(body))}"
        val expected = Hashing.hex(Hashing.hmacSha256(psk, canonical.toByteArray()))
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.lowercase().toByteArray())) return deny(response, "bad signature")

        identity.touch(deviceId)
        request.setAttribute(DEVICE_ATTR, device)
        request.setAttribute(PSK_ATTR, psk)
        chain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun deny(response: HttpServletResponse, reason: String) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.writer.write("""{"error":"$reason"}""")
    }

    private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val stream = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read() = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
                override fun isFinished() = stream.available() == 0
                override fun isReady() = true
                override fun setReadListener(listener: ReadListener?) = Unit
            }
        }
        override fun getContentLength() = body.size
        override fun getContentLengthLong() = body.size.toLong()
    }

    companion object {
        const val DEVICE_ATTR = "siren.device"
        const val PSK_ATTR = "siren.psk"
    }
}
