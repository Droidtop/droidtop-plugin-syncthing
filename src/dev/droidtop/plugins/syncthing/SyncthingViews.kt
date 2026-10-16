package dev.droidtop.plugins.syncthing

import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin's pages as droidtop view documents (docs/plugin-api.md 1.6): droidtop draws them in every mode, so they
 * look and move like its own settings. Syncthing's own web page is never shown; everything a person needs is here.
 */
internal object SyncthingViews {
    // Ops the pages call back on ui.settings.
    const val OP_FOLDER = "folder"
    const val OP_DEVICE = "device"
    const val OP_ADD_FOLDER_PAGE = "add_folder_page"
    const val OP_ADD_DEVICE_PAGE = "add_device_page"
    const val OP_LOG = "log"
    const val OP_SAVE = "save"
    const val OP_SCAN = "scan"
    const val OP_ADD_FOLDER = "add_folder"
    const val OP_REMOVE_FOLDER = "remove_folder"
    const val OP_SHARE_PAIRED = "share_paired"
    const val OP_SHARE = "share"
    const val OP_ADD_DEVICE = "add_device"
    const val OP_REMOVE_DEVICE = "remove_device"
    const val OP_ACCEPT_FOLDER = "accept_folder"
    const val OP_SHOW_PASSWORD = "show_password"

    const val IN_WIFI_ONLY = "wifi_only"
    const val IN_CHARGING_ONLY = "charging_only"
    const val IN_RESCAN = "rescan_minutes"
    const val IN_FOLDER_NAME = "folder_name"
    const val IN_FOLDER_PAIRED = "folder_paired"
    const val IN_DEVICE_ID = "device_id"
    const val IN_DEVICE_NAME = "device_name"
    const val IN_DEVICE_TRUST = "device_trust"
    const val TRUST_UNTRUSTED = "untrusted"
    const val TRUST_TRUSTED = "trusted"

    /** What the main page shows, gathered by the plugin. */
    data class Model(
        val state: JSONObject,
        val settings: SyncthingSettings,
        val waitingFor: String?,
        val serviceOn: Boolean,
        val charging: Boolean?,
        val paired: List<SyncthingEngine.PairedDevice>,
        val pairingProblem: String?,
        val problem: String?,
    )

    fun main(m: Model): JSONObject {
        val sections = ArrayList<JSONObject>()
        sections += statusSection(m)
        sections += whenSection(m)
        sections += foldersSection(m)
        pendingSection(m)?.let { sections += it }
        sections += pairedSection(m)
        sections += otherDevicesSection(m)
        sections += section(
            "about", "About",
            info("engine", "Syncthing", SyncthingVersion.UPSTREAM, "Upstream Syncthing, run by droidtop"),
            row("log", "Syncthing's log", null, null, open(OP_LOG)),
        )
        return view(null, null, sections)
    }

    private fun statusSection(m: Model): JSONObject {
        val running = m.state.optBoolean("running")
        val items = ArrayList<JSONObject>()
        m.problem?.let { items += notice("problem", "caution", "Syncthing could not start", it) }
        val status = when {
            running -> "Running"
            !m.serviceOn && m.waitingFor == null -> "Off"
            m.waitingFor != null -> m.waitingFor
            else -> "Starting"
        }
        val why = when {
            running -> null
            !m.serviceOn && m.waitingFor == null -> "Turn on its background service in this plugin's Permissions to sync."
            else -> null
        }
        items += info("status", "Sync", status, why)
        val error = m.state.optString("error").takeIf { it.isNotBlank() && !running }
        if (error != null && m.problem == null) items += notice("error", "caution", "Syncthing stopped", error)
        items += info("id", "This device's Syncthing ID", null, m.state.optString("deviceId").ifBlank { "Not made yet" })
        if (running) items += button("scan", "Look for changes now", "Scans every folder", call(OP_SCAN))
        return section("status", null, *items.toTypedArray())
    }

    private fun whenSection(m: Model): JSONObject {
        val items = arrayListOf(
            toggle(IN_WIFI_ONLY, "Sync only on Wi-Fi", "Or Ethernet. Mobile data is never used.", m.settings.wifiOnly, call(OP_SAVE)),
            toggle(
                IN_CHARGING_ONLY, "Sync only while charging",
                if (m.charging == null) "This handheld does not tell droidtop when it charges, so this has no effect." else null,
                m.settings.chargingOnly, call(OP_SAVE),
            ),
            slider(
                IN_RESCAN, "Look for changes every", "Minutes. Changes are found by scanning, not by watching.",
                SyncthingSettings.MIN_RESCAN_MINUTES, SyncthingSettings.MAX_RESCAN_MINUTES, m.settings.rescanMinutes, call(OP_SAVE),
            ),
        )
        return section("when", "When to sync", *items.toTypedArray())
    }

    private fun foldersSection(m: Model): JSONObject {
        val folders = m.state.optJSONArray("folders").objects()
        val items = ArrayList<JSONObject>()
        for (f in folders) {
            items += row(
                "folder_${items.size}", f.optString("label").ifBlank { f.optString("id") },
                folderLine(f, m.state.optBoolean("running")),
                JSONObject().put("number", f.optLong("globalBytes")).put("unit", "bytes"),
                open(OP_FOLDER, JSONObject().put("id", f.optString("id"))),
            )
        }
        if (folders.isEmpty()) items += info("no_folders", "No folders yet", null, "Add one, or accept one a device offers.")
        items += row("add_folder", "Add a folder", null, null, open(OP_ADD_FOLDER_PAGE))
        return section("folders", "Folders", *items.toTypedArray())
    }

    private fun folderLine(f: JSONObject, running: Boolean): String {
        val shared = f.optJSONArray("devices")?.length() ?: 0
        val sharing = when (shared) {
            0 -> "Not shared yet"
            1 -> "Shared with 1 device"
            else -> "Shared with $shared devices"
        }
        if (!running) return sharing
        return "${stateLabel(f.optString("state"))} · $sharing"
    }

    fun stateLabel(state: String): String = when (state) {
        "idle" -> "Up to date"
        "scanning", "scan-waiting" -> "Looking for changes"
        "syncing", "sync-waiting", "sync-preparing" -> "Syncing"
        "cleaning", "clean-waiting" -> "Cleaning up"
        "error" -> "Stopped with an error"
        "" -> "Not running"
        else -> state.replaceFirstChar { it.uppercase() }
    }

    private fun pendingSection(m: Model): JSONObject? {
        val pending = m.state.optJSONArray("pending").objects().filter { !it.optBoolean("encrypted") }
        if (pending.isEmpty()) return null
        val names = deviceNames(m.state)
        val items = pending.mapIndexed { i, p ->
            button(
                "pending_$i", p.optString("label").ifBlank { p.optString("id") },
                "Offered by ${names[p.optString("device")] ?: "a device"}. Accept to sync it here.",
                call(OP_ACCEPT_FOLDER, JSONObject().put("id", p.optString("id")).put("label", p.optString("label")).put("device", p.optString("device"))),
            )
        }
        return section("pending", "Offered to this device", *items.toTypedArray())
    }

    private fun pairedSection(m: Model): JSONObject {
        val connected = connectedIds(m.state)
        val items = ArrayList<JSONObject>()
        m.pairingProblem?.let { items += info("pairing", "Paired devices", null, it).put("needs", "devices.paired") }
        for ((i, d) in m.paired.withIndex()) {
            val value = when {
                d.syncthingId == null -> "No Syncthing yet"
                d.syncthingId in connected -> "Connected"
                m.state.optBoolean("running") -> "Not connected"
                else -> "Paired"
            }
            items += info("paired_$i", d.name, value, if (d.kind == "handheld") "Handheld" else "Computer")
        }
        if (m.paired.isEmpty() && m.pairingProblem == null) {
            items += info("no_paired", "No paired devices", null, "Pair a computer or another handheld with droidtop and it syncs here by itself.")
        }
        return section("paired", "Paired with droidtop", *items.toTypedArray())
    }

    private fun otherDevicesSection(m: Model): JSONObject {
        val pairedIds = m.settings.pairedIds
        val connected = connectedIds(m.state)
        val items = ArrayList<JSONObject>()
        for (d in m.state.optJSONArray("devices").objects().filter { it.optString("id") !in pairedIds }) {
            val id = d.optString("id")
            items += row(
                "device_${items.size}", d.optString("name").ifBlank { id.take(7) },
                if (d.optBoolean("untrusted")) "Untrusted: keeps an encrypted copy" else "Trusted",
                if (id in connected) "Connected" else if (m.state.optBoolean("running")) "Not connected" else null,
                open(OP_DEVICE, JSONObject().put("id", id)),
            )
        }
        items += row("add_device", "Add a Syncthing device", "Any Syncthing, by its device ID", null, open(OP_ADD_DEVICE_PAGE))
        return section("others", "Other Syncthing devices", *items.toTypedArray())
    }

    fun folder(state: JSONObject, settings: SyncthingSettings, id: String): JSONObject {
        val f = state.optJSONArray("folders").objects().firstOrNull { it.optString("id") == id }
            ?: return view("Folder", null, listOf(section("gone", null, info("gone", "This folder is no longer synced", null, null))))
        val running = state.optBoolean("running")
        val shared = f.optJSONArray("devices").objects().map { it.optString("id") }.toSet()
        val about = arrayListOf(
            info("state", "State", if (running) stateLabel(f.optString("state")) else "Not running", f.optString("error").ifBlank { null }),
            info("size", "Size", JSONObject().put("number", f.optLong("globalBytes")).put("unit", "bytes"), "${f.optInt("globalFiles")} files"),
            info("need", "Still to fetch", JSONObject().put("number", f.optLong("needBytes")).put("unit", "bytes")),
            info("path", "Kept in", null, f.optString("path")),
            info("folder_id", "Folder ID", f.optString("id")),
        )
        val sharing = arrayListOf(
            toggle(
                IN_FOLDER_PAIRED, "Share with my paired devices", "Every device paired with droidtop, now and later.",
                id in settings.sharedWithPaired, call(OP_SHARE_PAIRED, JSONObject().put("id", id)),
            ),
        )
        var untrustedShared = false
        for ((i, d) in state.optJSONArray("devices").objects().filter { it.optString("id") !in settings.pairedIds }.withIndex()) {
            val deviceId = d.optString("id")
            val on = deviceId in shared
            if (on && d.optBoolean("untrusted")) untrustedShared = true
            sharing += toggle(
                "share_$i", d.optString("name").ifBlank { deviceId.take(7) },
                if (d.optBoolean("untrusted")) "Untrusted: gets it encrypted" else null, on,
                call(OP_SHARE, JSONObject().put("id", id).put("device", deviceId).put("input", "share_$i")),
            )
        }
        val actions = ArrayList<JSONObject>()
        if (untrustedShared) {
            actions += button(
                "password", "Show the encryption password", "Needed to open the encrypted copy anywhere else",
                call(OP_SHOW_PASSWORD, JSONObject().put("id", id)),
            )
        }
        actions += button(
            "remove", "Stop syncing this folder", "Its files stay on this handheld",
            call(OP_REMOVE_FOLDER, JSONObject().put("id", id)),
        ).put("confirm", "Stop syncing ${f.optString("label").ifBlank { id }}? Its files stay here.")
        return view(
            f.optString("label").ifBlank { id }, null,
            listOf(section("about", null, *about.toTypedArray()), section("sharing", "Shared with", *sharing.toTypedArray()), section("actions", null, *actions.toTypedArray())),
        )
    }

    fun device(state: JSONObject, id: String): JSONObject {
        val d = state.optJSONArray("devices").objects().firstOrNull { it.optString("id") == id }
            ?: return view("Device", null, listOf(section("gone", null, info("gone", "This device was removed", null, null))))
        val name = d.optString("name").ifBlank { id.take(7) }
        val shared = state.optJSONArray("folders").objects()
        val items = arrayListOf(
            info("id", "Device ID", null, id),
            info("trust", "Trust", if (d.optBoolean("untrusted")) "Untrusted" else "Trusted", if (d.optBoolean("untrusted")) "It keeps an encrypted copy and never sees your files." else "It sees the files of the folders you share with it."),
            info("connected", "Connection", if (connectedIds(state).contains(id)) "Connected" else "Not connected"),
        )
        val folders = shared.mapIndexed { i, f ->
            val on = f.optJSONArray("devices").objects().any { it.optString("id") == id }
            toggle(
                "share_$i", f.optString("label").ifBlank { f.optString("id") }, null, on,
                call(OP_SHARE, JSONObject().put("id", f.optString("id")).put("device", id).put("input", "share_$i")),
            )
        }
        return view(
            name, null,
            listOf(
                section("about", null, *items.toTypedArray()),
                section("folders", "Folders shared with it", *(folders.ifEmpty { listOf(info("none", "No folders yet", null, null)) }).toTypedArray()),
                section(
                    "actions", null,
                    button("remove", "Remove this device", "It stops syncing with this handheld", call(OP_REMOVE_DEVICE, JSONObject().put("id", id)))
                        .put("confirm", "Remove $name? It stops syncing with this handheld."),
                ),
            ),
        )
    }

    fun addFolder(): JSONObject = view(
        "Add a folder", null,
        listOf(
            section(
                "new", null,
                text(IN_FOLDER_NAME, "Name", "Shown on every device it syncs with", ""),
                toggle(IN_FOLDER_PAIRED, "Share with my paired devices", null, true),
                button("add", "Add", "It is kept in this plugin's own storage", call(OP_ADD_FOLDER)),
            ),
        ),
    )

    fun addDevice(): JSONObject = view(
        "Add a Syncthing device", null,
        listOf(
            section(
                "new", null,
                text(IN_DEVICE_ID, "Device ID", "From that Syncthing: its Actions, Show ID", ""),
                text(IN_DEVICE_NAME, "Name", null, ""),
                choice(
                    IN_DEVICE_TRUST, "Trust", "An untrusted device, such as a backup server, never sees your files",
                    listOf(TRUST_UNTRUSTED to "Untrusted: keeps an encrypted copy", TRUST_TRUSTED to "Trusted: sees the files"),
                    TRUST_UNTRUSTED,
                ),
                button("add", "Add", null, call(OP_ADD_DEVICE)),
            ),
        ),
    )

    fun log(text: String): JSONObject {
        val lines = text.lines().filter { it.isNotBlank() }.takeLast(40)
        val items = if (lines.isEmpty()) {
            listOf(info("empty", "Nothing logged yet", null, null))
        } else {
            lines.mapIndexed { i, line -> info("l$i", line.take(200), null, line.drop(200).take(300).ifBlank { null }) }
        }
        return view("Syncthing's log", "The newest lines last", listOf(section("log", null, *items.toTypedArray())))
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun connectedIds(state: JSONObject): Set<String> =
        state.optJSONArray("devices").objects().filter { it.optBoolean("connected") }.map { it.optString("id") }.toSet()

    private fun deviceNames(state: JSONObject): Map<String, String> =
        state.optJSONArray("devices").objects().associate { it.optString("id") to it.optString("name").ifBlank { it.optString("id").take(7) } }

    private fun view(title: String?, subtitle: String?, sections: List<JSONObject>): JSONObject = JSONObject()
        .put("view", 1)
        .apply { title?.let { put("title", it) } }
        .apply { subtitle?.let { put("subtitle", it) } }
        .put("sections", JSONArray(sections))

    private fun section(id: String, title: String?, vararg items: JSONObject): JSONObject =
        JSONObject().put("id", id).put("title", title ?: JSONObject.NULL).put("items", JSONArray(items.toList()))

    private fun node(type: String, id: String, title: String, subtitle: String?): JSONObject =
        JSONObject().put("type", type).put("id", id).put("title", title).apply { subtitle?.let { put("subtitle", it) } }

    private fun info(id: String, title: String, value: Any?, subtitle: String? = null): JSONObject =
        node("info", id, title, subtitle).apply { value?.let { put("value", it) } }

    private fun row(id: String, title: String, subtitle: String?, value: Any?, action: JSONObject): JSONObject =
        node("row", id, title, subtitle).put("action", action).apply { value?.let { put("value", it) } }

    private fun button(id: String, title: String, subtitle: String?, action: JSONObject): JSONObject =
        node("button", id, title, subtitle).put("action", action)

    private fun toggle(id: String, title: String, subtitle: String?, value: Boolean, action: JSONObject? = null): JSONObject =
        node("toggle", id, title, subtitle).put("value", value).apply { action?.let { put("action", it) } }

    private fun slider(id: String, title: String, subtitle: String?, min: Int, max: Int, value: Int, action: JSONObject): JSONObject =
        node("slider", id, title, subtitle).put("min", min).put("max", max).put("value", value).put("action", action)

    private fun text(id: String, title: String, subtitle: String?, value: String): JSONObject =
        node("text", id, title, subtitle).put("value", value)

    private fun choice(id: String, title: String, subtitle: String?, options: List<Pair<String, String>>, value: String): JSONObject =
        node("choice", id, title, subtitle)
            .put("options", JSONArray(options.map { JSONObject().put("value", it.first).put("label", it.second) }))
            .put("value", value)

    private fun notice(id: String, tone: String, title: String, subtitle: String): JSONObject =
        JSONObject().put("type", "notice").put("id", id).put("tone", tone).put("title", title).put("subtitle", subtitle.take(140))

    private fun open(op: String, args: JSONObject = JSONObject()): JSONObject = JSONObject().put("kind", "view").put("op", op).put("args", args)

    private fun call(op: String, args: JSONObject = JSONObject()): JSONObject = JSONObject().put("kind", "call").put("op", op).put("args", args)
}
