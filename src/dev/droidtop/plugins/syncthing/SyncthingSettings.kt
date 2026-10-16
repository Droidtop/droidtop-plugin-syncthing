package dev.droidtop.plugins.syncthing

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the person chose, and what the plugin remembers beside Syncthing's own configuration: kept as JSON in the
 * plugin's own folder (droidtop's broker maps it for this process, docs/plugin-api.md 5.3 "The plugin's own folder by
 * path"). Syncthing's configuration stays the record of devices and folders; this holds only droidtop's side of them.
 */
internal data class SyncthingSettings(
    /** Sync only on Wi-Fi or Ethernet (the default: a sync can move a lot of data). */
    val wifiOnly: Boolean = true,
    /** Sync only while the handheld charges. */
    val chargingOnly: Boolean = false,
    /** How often folders are scanned for changes, in minutes: the sandbox refuses a file watcher. */
    val rescanMinutes: Int = 5,
    /** The Syncthing IDs this plugin added because droidtop paired their devices; only these are removed again. */
    val pairedIds: Set<String> = emptySet(),
    /** Folders that are shared with every device droidtop paired (new folders are, unless the person says not). */
    val sharedWithPaired: Set<String> = emptySet(),
    /** Whether the folder every droidtop shares ("droidtop") was made once; the person may remove it. */
    val defaultFolderMade: Boolean = false,
    /** The value last announced to the paired devices, so an unchanged one is not sent again. */
    val announced: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("wifiOnly", wifiOnly)
        .put("chargingOnly", chargingOnly)
        .put("rescanMinutes", rescanMinutes)
        .put("pairedIds", JSONArray(pairedIds.sorted()))
        .put("sharedWithPaired", JSONArray(sharedWithPaired.sorted()))
        .put("defaultFolderMade", defaultFolderMade)
        .put("announced", announced ?: JSONObject.NULL)

    companion object {
        const val MIN_RESCAN_MINUTES = 1
        const val MAX_RESCAN_MINUTES = 60

        fun fromJson(json: JSONObject): SyncthingSettings = SyncthingSettings(
            wifiOnly = json.optBoolean("wifiOnly", true),
            chargingOnly = json.optBoolean("chargingOnly", false),
            rescanMinutes = json.optInt("rescanMinutes", 5).coerceIn(MIN_RESCAN_MINUTES, MAX_RESCAN_MINUTES),
            pairedIds = json.optJSONArray("pairedIds").strings(),
            sharedWithPaired = json.optJSONArray("sharedWithPaired").strings(),
            defaultFolderMade = json.optBoolean("defaultFolderMade", false),
            announced = json.optString("announced").takeUnless { json.isNull("announced") || it.isBlank() },
        )

        private fun JSONArray?.strings(): Set<String> {
            val array = this ?: return emptySet()
            return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
        }
    }
}

/** The settings file, read and written whole under one lock. */
internal class SettingsStore(private val file: File) {
    private val lock = Any()

    fun read(): SyncthingSettings = synchronized(lock) {
        runCatching { SyncthingSettings.fromJson(JSONObject(file.readText())) }.getOrDefault(SyncthingSettings())
    }

    fun update(change: (SyncthingSettings) -> SyncthingSettings): SyncthingSettings = synchronized(lock) {
        val next = change(read())
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(next.toJson().toString(2))
        if (!temp.renameTo(file)) {
            file.writeText(next.toJson().toString(2))
            temp.delete()
        }
        next
    }
}
