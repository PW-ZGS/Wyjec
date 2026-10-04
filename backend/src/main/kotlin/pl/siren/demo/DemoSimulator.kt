package pl.siren.demo

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.siren.alarm.AlarmQueryService
import pl.siren.alarm.InstanceSummary
import pl.siren.alarm.Participant
import pl.siren.config.SirenProperties
import pl.siren.identity.IdentityRepository
import pl.siren.live.LiveUpdates
import pl.siren.status.PositionReport
import pl.siren.status.ReceptionReport
import pl.siren.status.StatusBatch
import pl.siren.status.StatusService
import pl.siren.status.TaskReport
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class SimulatedDevice(
    val deviceId: UUID,
    val personId: UUID,
    val displayName: String,
    val role: String?,
    val organizationName: String,
    val domain: String,
    val simulated: Boolean,
    val lastSeenAt: Instant?,
)

/**
 * Plays responders' phones during a demo so one laptop can show a full response: reception,
 * acknowledgement, movement to the assigned location and task progress. Writes through the same
 * [StatusService] real devices use. Only active with the `demo` profile.
 */
@Service
class DemoSimulator(
    private val jdbc: NamedParameterJdbcTemplate,
    private val queries: AlarmQueryService,
    private val status: StatusService,
    private val identity: IdentityRepository,
    private val props: SirenProperties,
    private val live: LiveUpdates,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val autopilot = AtomicBoolean(true)
    private val plans = ConcurrentHashMap<String, Plan>()

    private data class Point(val lat: Double, val lon: Double)
    private data class Leg(val from: Double, val to: Double, val start: Point, val end: Point)
    private data class Step(val at: Double, val batch: (Instant) -> StatusBatch)
    private class Plan(val legs: List<Leg>, val steps: List<Step>, val positionsFrom: Double) {
        var emitted = 0
        var lastPosition: Point? = null
    }

    fun devices(): List<SimulatedDevice> =
        jdbc.query(
            """
            SELECT d.id, d.last_seen_at, p.id AS person_id, p.display_name, p.role, o.name AS org_name, o.domain,
                   (s.device_id IS NOT NULL) AS simulated
            FROM device d JOIN person p ON p.id = d.person_id JOIN organization o ON o.id = p.organization_id
            LEFT JOIN demo_simulated_device s ON s.device_id = d.id
            WHERE d.platform <> 'DESKTOP'
            ORDER BY o.domain, o.name, p.display_name
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            SimulatedDevice(
                deviceId = rs.getObject("id", UUID::class.java),
                personId = rs.getObject("person_id", UUID::class.java),
                displayName = rs.getString("display_name"),
                role = rs.getString("role"),
                organizationName = rs.getString("org_name"),
                domain = rs.getString("domain"),
                simulated = rs.getBoolean("simulated"),
                lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant(),
            )
        }

    fun setSimulated(deviceId: UUID, simulated: Boolean) {
        if (simulated) {
            jdbc.update("INSERT INTO demo_simulated_device (device_id) VALUES (:d) ON CONFLICT DO NOTHING", mapOf("d" to deviceId))
        } else {
            jdbc.update("DELETE FROM demo_simulated_device WHERE device_id = :d", mapOf("d" to deviceId))
            plans.keys.removeIf { it.endsWith("/$deviceId") }
        }
        live.publish("simulator")
    }

    fun release(deviceId: UUID) {
        val removed = jdbc.update("DELETE FROM demo_simulated_device WHERE device_id = :d", mapOf("d" to deviceId))
        if (removed > 0) {
            plans.keys.removeIf { it.endsWith("/$deviceId") }
            log.info("Device {} is a real phone now; autopilot released it", deviceId)
            live.publish("simulator")
        }
    }

    /** Manual control from the simulator page: report as if the phone sent it. */
    fun report(deviceId: UUID, batch: StatusBatch) {
        val device = identity.findDevice(deviceId) ?: throw NoSuchElementException("Unknown device")
        status.record(device, batch)
    }

    @Scheduled(fixedDelay = 1500, initialDelay = 5000)
    fun tick() {
        if (!props.demo.enabled || !autopilot.get()) return
        val simulated = jdbc.queryForList("SELECT device_id FROM demo_simulated_device", emptyMap<String, Any>(), UUID::class.java).toSet()
        val now = Instant.now()
        val active = queries.active()
        plans.keys.retainAll { key -> active.any { key.startsWith(it.id.toString()) } }
        for (instance in active) {
            if (Duration.between(instance.raisedAt, now) > Duration.ofHours(2)) continue
            for (participant in queries.participants(instance)) {
                val deviceId = participant.deviceId ?: continue
                if (deviceId !in simulated) continue
                val plan = plans.computeIfAbsent("${instance.id}/$deviceId") { plan(instance, participant) }
                runCatching { advance(instance, deviceId, plan, now) }
                    .onFailure { log.warn("Simulator step failed for {}: {}", participant.displayName, it.message) }
            }
        }
    }

    private fun advance(instance: InstanceSummary, deviceId: UUID, plan: Plan, now: Instant) {
        val device = identity.findDevice(deviceId) ?: return
        val t = Duration.between(instance.raisedAt, now).toMillis() / 1000.0
        val reports = mutableListOf<StatusBatch>()
        while (plan.emitted < plan.steps.size && plan.steps[plan.emitted].at <= t) {
            reports += plan.steps[plan.emitted].batch(now)
            plan.emitted++
        }
        if (t >= plan.positionsFrom && device.mayReportPosition) {
            val pos = positionAt(plan, t)
            if (pos != plan.lastPosition) {
                plan.lastPosition = pos
                reports += StatusBatch(positions = listOf(PositionReport(instance.id, pos.lat, pos.lon, 8.0, now)))
            }
        }
        if (reports.isNotEmpty()) {
            status.record(device, StatusBatch(
                receptions = reports.flatMap { it.receptions },
                tasks = reports.flatMap { it.tasks },
                positions = reports.flatMap { it.positions },
            ))
        }
    }

    private fun positionAt(plan: Plan, t: Double): Point {
        val leg = plan.legs.lastOrNull { t >= it.from } ?: return plan.legs.first().start
        if (leg.to <= leg.from) return leg.end
        val f = ((t - leg.from) / (leg.to - leg.from)).coerceIn(0.0, 1.0)
        return Point(leg.start.lat + (leg.end.lat - leg.start.lat) * f, leg.start.lon + (leg.end.lon - leg.start.lon) * f)
    }

    /** A believable, per-person timeline: some react fast, one in five is slow to read the alarm. */
    private fun plan(instance: InstanceSummary, p: Participant): Plan {
        val rnd = Random((instance.id.toString() + p.personId).hashCode())
        val slow = rnd.nextInt(5) == 0
        val receiveAt = rnd.nextDouble(1.0, 4.0)
        val ackAt = receiveAt + if (slow) rnd.nextDouble(35.0, 55.0) else rnd.nextDouble(4.0, 14.0)
        val steps = mutableListOf<Step>()
        steps += Step(receiveAt) { now -> StatusBatch(receptions = listOf(ReceptionReport(instance.id, now, "INTERNET", 0))) }
        steps += Step(ackAt) { now -> StatusBatch(receptions = listOf(ReceptionReport(instance.id, now.minusSeconds((ackAt - receiveAt).toLong()), "INTERNET", 0, now))) }

        val firstTarget = p.groups.firstNotNullOfOrNull { it.location }?.let { Point(it.lat, it.lon) } ?: Point(50.06, 19.94)
        val bearing = rnd.nextDouble(0.0, 2 * Math.PI)
        val startDistance = rnd.nextDouble(450.0, 900.0)
        var here = offset(firstTarget, startDistance, bearing)
        val legs = mutableListOf(Leg(0.0, 0.0, here, here))
        var t = ackAt + 2
        for (group in p.groups) {
            val target = group.location?.let { Point(it.lat, it.lon) } ?: here
            val travel = (distance(here, target) / rnd.nextDouble(18.0, 30.0)).coerceIn(8.0, 45.0)
            val tasks = group.tasks.sortedBy { it.orderNo }
            val first = tasks.firstOrNull()
            if (first != null) steps += taskStep(t, instance.id, first.taskId, "EN_ROUTE")
            legs += Leg(t, t + travel, here, target)
            t += travel
            here = target
            if (first != null) steps += taskStep(t, instance.id, first.taskId, "DONE")
            for (task in tasks.drop(1)) {
                t += rnd.nextDouble(1.0, 3.0)
                steps += taskStep(t, instance.id, task.taskId, "IN_PROGRESS")
                t += rnd.nextDouble(6.0, 14.0)
                steps += taskStep(t, instance.id, task.taskId, "DONE")
            }
            t += 2
        }
        return Plan(legs, steps.sortedBy { it.at }, receiveAt)
    }

    private fun taskStep(at: Double, instanceId: UUID, taskId: UUID, state: String) =
        Step(at) { now -> StatusBatch(tasks = listOf(TaskReport(instanceId, taskId, state, now))) }

    private fun offset(p: Point, meters: Double, bearing: Double): Point {
        val dLat = meters * cos(bearing) / 111_320.0
        val dLon = meters * sin(bearing) / (111_320.0 * cos(Math.toRadians(p.lat)))
        return Point(p.lat + dLat, p.lon + dLon)
    }

    private fun distance(a: Point, b: Point): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
