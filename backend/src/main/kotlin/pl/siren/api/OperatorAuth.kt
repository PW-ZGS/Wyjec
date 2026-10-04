package pl.siren.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class OperatorSession(
    val token: String,
    val username: String,
    val personId: UUID,
    val displayName: String,
    val organizationName: String,
    val consoleDeviceId: UUID,
)

/** Admin panel login. Prototype: bcrypt passwords in DB, opaque bearer tokens kept in memory. */
@Component
class OperatorSessions(private val jdbc: NamedParameterJdbcTemplate) {
    private val sessions = ConcurrentHashMap<String, OperatorSession>()
    private val encoder = BCryptPasswordEncoder()
    private val random = SecureRandom()

    fun login(username: String, password: String): OperatorSession? {
        val row = jdbc.query(
            """
            SELECT a.username, a.password_hash, a.person_id, a.console_device_id, p.display_name, o.name AS org_name
            FROM operator_account a JOIN person p ON p.id = a.person_id JOIN organization o ON o.id = p.organization_id
            WHERE a.username = :u
            """.trimIndent(),
            mapOf("u" to username),
        ) { rs, _ ->
            rs.getString("password_hash") to OperatorSession(
                token = "",
                username = rs.getString("username"),
                personId = rs.getObject("person_id", UUID::class.java),
                displayName = rs.getString("display_name"),
                organizationName = rs.getString("org_name"),
                consoleDeviceId = rs.getObject("console_device_id", UUID::class.java),
            )
        }.firstOrNull() ?: return null
        if (!encoder.matches(password, row.first)) return null
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        return row.second.copy(token = token).also { sessions[token] = it }
    }

    fun find(token: String?): OperatorSession? = token?.let { sessions[it] }

    fun logout(token: String) {
        sessions.remove(token)
    }
}

@Component
class OperatorAuthFilter(private val sessions: OperatorSessions) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest) =
        !request.requestURI.startsWith("/api/admin/") || request.requestURI == "/api/admin/login"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // EventSource cannot send headers, so the SSE stream accepts ?token=.
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim() ?: request.getParameter("token")
        val session = sessions.find(token)
        if (session == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"not logged in"}""")
            return
        }
        request.setAttribute(SESSION_ATTR, session)
        chain.doFilter(request, response)
    }

    companion object {
        const val SESSION_ATTR = "siren.operator"
    }
}
