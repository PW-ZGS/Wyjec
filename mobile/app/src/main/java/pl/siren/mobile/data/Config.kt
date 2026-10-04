package pl.siren.mobile.data

import android.content.Context
import androidx.core.content.edit

/** Device enrolment and small settings. Prototype: plain SharedPreferences; production keeps the PSK in the TEE keystore. */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("siren", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("serverUrl", "") ?: ""
        set(v) = prefs.edit { putString("serverUrl", v.trimEnd('/')) }

    var deviceId: String
        get() = prefs.getString("deviceId", "") ?: ""
        set(v) = prefs.edit { putString("deviceId", v.trim()) }

    var psk: String
        get() = prefs.getString("psk", "") ?: ""
        set(v) = prefs.edit { putString("psk", v) }

    /** Server time of the last alarm feed, used as the next `since` cursor. */
    var feedCursor: Long
        get() = prefs.getLong("feedCursor", 0L)
        set(v) = prefs.edit { putLong("feedCursor", v) }

    var lastSyncAt: Long
        get() = prefs.getLong("lastSyncAt", 0L)
        set(v) = prefs.edit { putLong("lastSyncAt", v) }

    /** Last sync response sections the UI needs (person, controls, device). */
    var profileJson: String
        get() = prefs.getString("profile", "") ?: ""
        set(v) = prefs.edit { putString("profile", v) }

    val enrolled get() = serverUrl.isNotBlank() && deviceId.isNotBlank() && psk.isNotBlank()

    fun clear() = prefs.edit { clear() }
}
