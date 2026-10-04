package pl.siren.mobile.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class LocalKey(
    val epochId: Int,
    val organizationId: String,
    val publicKey: ByteArray,
    val signingKey: ByteArray?,
    val validFrom: Long,
    val validTo: Long,
    val status: String,
)

data class Place(val name: String, val address: String?, val lat: Double, val lon: Double)
data class Task(val id: String, val orderNo: Int, val description: String, val requiresConfirmation: Boolean)
data class TaskGroup(val id: String, val name: String, val location: Place?, val tasks: List<Task>)

data class LocalScenario(
    val versionId: String,
    val definitionId: String,
    val code: String,
    val name: String,
    val version: Int,
    val bundle: ByteArray,
    val sha256: ByteArray,
) {
    val groups: List<TaskGroup> by lazy {
        val json = JSONObject(String(bundle))
        val arr = json.getJSONArray("taskGroups")
        (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            val loc = g.optJSONObject("location")
            val tasks = g.getJSONArray("tasks")
            TaskGroup(
                id = g.getString("id"),
                name = g.getString("name"),
                location = loc?.let { Place(it.getString("name"), it.optString("address").ifBlank { null }, it.getDouble("lat"), it.getDouble("lon")) },
                tasks = (0 until tasks.length()).map { j ->
                    val t = tasks.getJSONObject(j)
                    Task(t.getString("id"), t.getInt("orderNo"), t.getString("description"), t.optBoolean("requiresConfirmation"))
                }.sortedBy { it.orderNo },
            )
        }
    }
}

data class Area(val lat: Double, val lon: Double, val radiusM: Int)

data class LocalAlarm(
    val instanceId: String,
    val definitionId: String,
    val code: String,
    val title: String,
    val scenarioVersionId: String,
    val raisedAt: Long,
    val cancelledAt: Long?,
    val receivedAt: Long,
    val viaBearer: String,
    val hopCount: Int,
    val acknowledgedAt: Long?,
    val area: Area?,
) {
    val active get() = cancelledAt == null
}

data class OutboxItem(val id: Long, val kind: String, val payload: JSONObject, val attempts: Int)

data class SeenAlarm(val eventId: String, val payload: ByteArray, val signature: ByteArray, val hopCount: Int, val firstBearer: String)

/**
 * Edge local store (schema "Edge local store" in siren_db_schema.png) plus two prototype tables for
 * the alarm screen: local_alarm (instances seen) and local_task_state (this person's progress).
 * Production encrypts it (SQLCipher) and keeps signing keys in the TEE keystore.
 */
class LocalStore(context: Context) : SQLiteOpenHelper(context, "siren.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE local_key (key_epoch_id INTEGER PRIMARY KEY, organization_id TEXT NOT NULL, public_key BLOB NOT NULL,
               signing_key BLOB, valid_from INTEGER NOT NULL, valid_to INTEGER NOT NULL, status TEXT NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE local_scenario (scenario_version_id TEXT PRIMARY KEY, alarm_definition_id TEXT NOT NULL,
               alarm_code TEXT NOT NULL, alarm_name TEXT NOT NULL, version INTEGER NOT NULL, bundle BLOB NOT NULL, bundle_sha256 BLOB NOT NULL)""",
        )
        db.execSQL("CREATE TABLE local_policy (id INTEGER PRIMARY KEY, relay_mode TEXT NOT NULL, allowed_bearers TEXT NOT NULL, signed_by_server BLOB)")
        db.execSQL(
            """CREATE TABLE seen_alarm (alarm_event_id TEXT PRIMARY KEY, alarm_instance_id TEXT NOT NULL, event_type TEXT NOT NULL,
               created_at INTEGER NOT NULL, first_seen_at INTEGER NOT NULL, forwarded INTEGER NOT NULL DEFAULT 0, hop_count INTEGER NOT NULL,
               payload BLOB NOT NULL, signature BLOB NOT NULL, first_bearer TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE status_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, payload BLOB NOT NULL,
               created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE local_alarm (instance_id TEXT PRIMARY KEY, definition_id TEXT NOT NULL, code TEXT NOT NULL, title TEXT NOT NULL,
               scenario_version_id TEXT NOT NULL, raised_at INTEGER NOT NULL, cancelled_at INTEGER, received_at INTEGER NOT NULL,
               via_bearer TEXT NOT NULL, hop_count INTEGER NOT NULL, acknowledged_at INTEGER, area_lat REAL, area_lon REAL, area_radius INTEGER)""",
        )
        db.execSQL(
            """CREATE TABLE local_task_state (instance_id TEXT NOT NULL, task_id TEXT NOT NULL, state TEXT NOT NULL,
               updated_at INTEGER NOT NULL, PRIMARY KEY (instance_id, task_id))""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ───── keys ─────
    fun replaceKeys(keys: List<LocalKey>) = writableDatabase.tx {
        delete("local_key", null, null)
        keys.forEach { k ->
            insert("local_key", null, ContentValues().apply {
                put("key_epoch_id", k.epochId); put("organization_id", k.organizationId); put("public_key", k.publicKey)
                put("signing_key", k.signingKey); put("valid_from", k.validFrom); put("valid_to", k.validTo); put("status", k.status)
            })
        }
    }

    fun keys(): List<LocalKey> = readableDatabase.query("local_key", null, null, null, null, null, "key_epoch_id").use { c ->
        c.all {
            LocalKey(int("key_epoch_id"), str("organization_id"), blob("public_key"), blobOrNull("signing_key"), long("valid_from"), long("valid_to"), str("status"))
        }
    }

    // ───── scenarios ─────
    fun replaceScenarios(list: List<LocalScenario>) = writableDatabase.tx {
        delete("local_scenario", null, null)
        list.forEach { s ->
            insert("local_scenario", null, ContentValues().apply {
                put("scenario_version_id", s.versionId); put("alarm_definition_id", s.definitionId); put("alarm_code", s.code)
                put("alarm_name", s.name); put("version", s.version); put("bundle", s.bundle); put("bundle_sha256", s.sha256)
            })
        }
    }

    fun scenarios(): List<LocalScenario> = readableDatabase.query("local_scenario", null, null, null, null, null, "alarm_name").use { c ->
        c.all { LocalScenario(str("scenario_version_id"), str("alarm_definition_id"), str("alarm_code"), str("alarm_name"), int("version"), blob("bundle"), blob("bundle_sha256")) }
    }

    // ───── policy ─────
    fun savePolicy(relayMode: String, bearers: List<String>, signature: ByteArray) = writableDatabase.tx {
        insertWithOnConflict("local_policy", null, ContentValues().apply {
            put("id", 1); put("relay_mode", relayMode); put("allowed_bearers", bearers.joinToString(",")); put("signed_by_server", signature)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun relayMode(): String = readableDatabase.rawQuery("SELECT relay_mode FROM local_policy WHERE id = 1", null).use { c ->
        if (c.moveToFirst()) c.getString(0) else "RECEIVE_ONLY"
    }

    // ───── seen alarms (dedup / replay) ─────
    fun isSeen(eventId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM seen_alarm WHERE alarm_event_id = ?", arrayOf(eventId)).use { it.moveToFirst() }

    fun markSeen(eventId: String, instanceId: String, type: String, createdAt: Long, hop: Int, payload: ByteArray, signature: ByteArray, bearer: String, uploaded: Boolean) =
        writableDatabase.insertWithOnConflict("seen_alarm", null, ContentValues().apply {
            put("alarm_event_id", eventId); put("alarm_instance_id", instanceId); put("event_type", type); put("created_at", createdAt)
            put("first_seen_at", System.currentTimeMillis()); put("hop_count", hop); put("payload", payload); put("signature", signature)
            put("first_bearer", bearer); put("uploaded", if (uploaded) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)

    fun markForwarded(eventId: String) =
        writableDatabase.execSQL("UPDATE seen_alarm SET forwarded = 1 WHERE alarm_event_id = ?", arrayOf(eventId))

    fun notUploaded(): List<SeenAlarm> =
        readableDatabase.rawQuery("SELECT * FROM seen_alarm WHERE uploaded = 0 ORDER BY created_at", null).use { c ->
            c.all { SeenAlarm(str("alarm_event_id"), blob("payload"), blob("signature"), int("hop_count"), str("first_bearer")) }
        }

    fun markUploaded(eventId: String) =
        writableDatabase.execSQL("UPDATE seen_alarm SET uploaded = 1 WHERE alarm_event_id = ?", arrayOf(eventId))

    fun cancelExists(instanceId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM seen_alarm WHERE alarm_instance_id = ? AND event_type = 'CANCEL'", arrayOf(instanceId)).use { it.moveToFirst() }

    // ───── alarm instances ─────
    fun upsertRaise(a: LocalAlarm) = writableDatabase.insertWithOnConflict("local_alarm", null, ContentValues().apply {
        put("instance_id", a.instanceId); put("definition_id", a.definitionId); put("code", a.code); put("title", a.title)
        put("scenario_version_id", a.scenarioVersionId); put("raised_at", a.raisedAt); put("received_at", a.receivedAt)
        put("via_bearer", a.viaBearer); put("hop_count", a.hopCount)
        a.cancelledAt?.let { put("cancelled_at", it) }
        a.area?.let { put("area_lat", it.lat); put("area_lon", it.lon); put("area_radius", it.radiusM) }
    }, SQLiteDatabase.CONFLICT_IGNORE)

    fun markCancelled(instanceId: String, at: Long) =
        writableDatabase.execSQL("UPDATE local_alarm SET cancelled_at = ? WHERE instance_id = ? AND cancelled_at IS NULL", arrayOf<Any>(at, instanceId))

    fun acknowledge(instanceId: String, at: Long) =
        writableDatabase.execSQL("UPDATE local_alarm SET acknowledged_at = ? WHERE instance_id = ? AND acknowledged_at IS NULL", arrayOf<Any>(at, instanceId))

    fun alarms(): List<LocalAlarm> = readableDatabase.query("local_alarm", null, null, null, null, null, "raised_at DESC", "20").use { c ->
        c.all {
            LocalAlarm(
                str("instance_id"), str("definition_id"), str("code"), str("title"), str("scenario_version_id"), long("raised_at"),
                longOrNull("cancelled_at"), long("received_at"), str("via_bearer"), int("hop_count"), longOrNull("acknowledged_at"),
                if (isNull(getColumnIndexOrThrow("area_lat"))) null else Area(double("area_lat"), double("area_lon"), int("area_radius")),
            )
        }
    }

    // ───── task progress ─────
    fun setTaskState(instanceId: String, taskId: String, state: String) =
        writableDatabase.insertWithOnConflict("local_task_state", null, ContentValues().apply {
            put("instance_id", instanceId); put("task_id", taskId); put("state", state); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)

    fun taskStates(instanceId: String): Map<String, String> =
        readableDatabase.rawQuery("SELECT task_id, state FROM local_task_state WHERE instance_id = ?", arrayOf(instanceId)).use { c ->
            c.all { str("task_id") to str("state") }.toMap()
        }

    // ───── status outbox (store and forward) ─────
    fun enqueue(kind: String, payload: JSONObject) = writableDatabase.insert("status_outbox", null, ContentValues().apply {
        put("kind", kind); put("payload", payload.toString().toByteArray()); put("created_at", System.currentTimeMillis())
    })

    fun outbox(limit: Int = 100): List<OutboxItem> =
        readableDatabase.query("status_outbox", null, null, null, null, null, "id", limit.toString()).use { c ->
            c.all { OutboxItem(long("id"), str("kind"), JSONObject(String(blob("payload"))), int("attempts")) }
        }

    fun outboxSize(): Int = readableDatabase.rawQuery("SELECT count(*) FROM status_outbox", null).use { it.moveToFirst(); it.getInt(0) }

    fun removeOutbox(ids: List<Long>) = writableDatabase.tx { ids.forEach { delete("status_outbox", "id = ?", arrayOf(it.toString())) } }

    fun bumpOutbox(ids: List<Long>) = writableDatabase.tx { ids.forEach { execSQL("UPDATE status_outbox SET attempts = attempts + 1 WHERE id = ?", arrayOf(it)) } }

    fun wipe() = writableDatabase.tx {
        listOf("local_key", "local_scenario", "local_policy", "seen_alarm", "status_outbox", "local_alarm", "local_task_state").forEach { delete(it, null, null) }
    }
}

private inline fun SQLiteDatabase.tx(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private inline fun <T> Cursor.all(row: Cursor.() -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += row()
    return out
}

private fun Cursor.str(c: String) = getString(getColumnIndexOrThrow(c))
private fun Cursor.int(c: String) = getInt(getColumnIndexOrThrow(c))
private fun Cursor.long(c: String) = getLong(getColumnIndexOrThrow(c))
private fun Cursor.double(c: String) = getDouble(getColumnIndexOrThrow(c))
private fun Cursor.blob(c: String) = getBlob(getColumnIndexOrThrow(c))
private fun Cursor.blobOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getBlob(it) }
private fun Cursor.longOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getLong(it) }
