package app.copyphotos.data

import android.content.SharedPreferences
import app.copyphotos.core.DestinationConfig
import app.copyphotos.core.DestinationStore
import app.copyphotos.core.SmbAuth
import app.copyphotos.core.VerifyMode
import org.json.JSONObject

/**
 * The [DestinationStore] on Android: the settings in SharedPreferences (as JSON), the password
 * in a [PasswordStore]. `DestinationService` is its only user.
 *
 * @param prefs the preferences file the settings are kept in.
 * @param passwords where the password is saved; shared with the connector, which reads it.
 */
class DestinationPreferences(private val prefs: SharedPreferences, private val passwords: PasswordStore) : DestinationStore {

    override fun loadConfig(): DestinationConfig = fromJson(prefs.getString(KEY_CONFIG, null))

    override fun saveConfig(config: DestinationConfig) {
        prefs.edit().putString(KEY_CONFIG, toJson(config)).apply()
    }

    override val hasPassword: Boolean get() = passwords.has

    override fun setPassword(password: String) = passwords.set(password)

    private fun toJson(c: DestinationConfig): String = JSONObject().apply {
        put("host", c.host)
        c.port?.let { put("port", it) }
        put("share", c.share)
        put("path", c.path)
        put("smbAuth", c.smbAuth.name)
        put("username", c.username)
        put("domain", c.domain)
        put("verifyMode", c.verifyMode.name)
        put("autoCopy", c.autoCopy)
        put("autoCopyWifiOnly", c.autoCopyWifiOnly)
        put("autoCopyWhileCharging", c.autoCopyWhileCharging)
    }.toString()

    private fun fromJson(json: String?): DestinationConfig {
        if (json == null) return DestinationConfig()
        return runCatching {
            val o = JSONObject(json)
            DestinationConfig(
                host = o.getString("host"),
                port = if (o.has("port")) o.getInt("port") else null,
                share = o.getString("share"),
                path = o.getString("path"),
                smbAuth = SmbAuth.valueOf(o.getString("smbAuth")),
                username = o.getString("username"),
                domain = o.getString("domain"),
                verifyMode = VerifyMode.valueOf(o.getString("verifyMode")),
                autoCopy = o.getBoolean("autoCopy"),
                autoCopyWifiOnly = o.getBoolean("autoCopyWifiOnly"),
                autoCopyWhileCharging = o.getBoolean("autoCopyWhileCharging"),
            )
        }.getOrDefault(DestinationConfig())
    }

    private companion object {
        const val KEY_CONFIG = "destination"
    }
}
