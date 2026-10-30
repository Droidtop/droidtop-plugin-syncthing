package dev.droidtop.plugins.syncthing

import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin as facets (docs/plugin-api.md 1.10, contract 3): droidtop draws them on its own targets with its own
 * components. The plugin's page holds everything a person needs; its settings page holds when to sync; the Quick Menu
 * says whether it is syncing. Syncthing's own web page is never shown. Pure, for the tests.
 */
internal object SyncthingFacets {
    const val PLUGIN_PAGE = "plugin.page"
    const val SETTINGS_PAGE = "settings.page"
    const val QUICK_MENU = "quickmenu"
    val TARGETS = listOf(PLUGIN_PAGE, SETTINGS_PAGE, QUICK_MENU)

    // Ops the facets call back on ui.facets.
    const val OP_GET = "get"
    const val OP_FOLDER = "folder"
    const val OP_DEVICE = "device"
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

    // Facet ids whose values come back in args.values.
    const val IN_WIFI_ONLY = "wifi_only"
    const val IN_CHARGING_ONLY = "charging_only"
    const val IN_RESCAN = "rescan_minutes"
    const val IN_FOLDER_NAME = "folder_name"
    const val IN_FOLDER_PAIRED = "folder_paired"
    const val IN_FOLDER_PATH = "folder_path"
    const val IN_DEVICE_ID = "device_id"
    const val IN_DEVICE_NAME = "device_name"
    const val IN_DEVICE_TRUST = "device_trust"
    const val TRUST_UNTRUSTED = "untrusted"
    const val TRUST_TRUSTED = "trusted"

    const val PAIRED_PERMISSION = "devices.paired"

    /** What the pages show, gathered by the plugin. */
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

    /** The facets of [target]; empty for a target the plugin does not draw on. */
    fun forTarget(target: String, m: Model): JSONObject = when (target) {
        PLUGIN_PAGE -> reply(null, pluginPage(m))
        SETTINGS_PAGE -> reply(null, listOf(whenGroup(m, SETTINGS_PAGE)))
        QUICK_MENU -> reply(null, quickMenu(m))
        else -> reply(null, emptyList())
    }

    // -----------------------------------------------------------------
    // The plugin's page
    // -----------------------------------------------------------------

    fun pluginPage(m: Model): List<JSONObject> {
        val t = PLUGIN_PAGE
        val out = ArrayList<JSONObject>()
        val running = m.state.optBoolean("running")
        m.problem?.let { out += notice("problem", t, "Syncthing could not start", it, "caution") }
        val error = m.state.optString("error").takeIf { it.isNotBlank() && !running }
        if (error != null && m.problem == null) out += notice("stopped", t, "Syncthing stopped", error, "caution")
        out += status("sync", t, m)
        out += info("my_id", t, "This device's Syncthing ID", m.state.optString("deviceId").ifBlank { "Not made yet" })
        if (running) out += action("scan", t, "Look for changes now", "Scans every folder", call(OP_SCAN))
        out += whenGroup(m, t)
        out += foldersGroup(m, t)
        pendingGroup(m, t)?.let { out += it }
        out += pairedGroup(m, t)
        out += devicesGroup(m, t)
        out += addFolderForm(t)
        out += addDeviceForm(t)
        out += group(
            "about", t, "About",
            info("engine", null, "Syncthing", SyncthingVersion.UPSTREAM, "Upstream Syncthing, run by droidtop"),
            action("log", null, "Syncthing's log", null, page(OP_LOG)),
        )
        return out
    }

    /** The sync state as a status facet: on, off, waiting or an error, and droidtop's words for it. */
    fun status(id: String, target: String, m: Model): JSONObject {
        val running = m.state.optBoolean("running")
        val (state, value) = when {
            m.problem != null -> "error" to "Could not start"
            running -> "on" to "Running"
            !m.serviceOn && m.waitingFor == null -> "off" to "Off"
            m.waitingFor != null -> "busy" to m.waitingFor
            else -> "busy" to "Starting"
        }
        return facet(id, "status", target).put("label", "Sync").put("state", state).put("value", value)
    }

    private fun quickMenu(m: Model): List<JSONObject> = buildList {
        add(status("sync", QUICK_MENU, m))
        if (m.state.optBoolean("running")) add(action("scan", QUICK_MENU, "Look for changes now", null, call(OP_SCAN)))
    }

    /** When to sync: each switch and the slider save as they change. */
    fun whenGroup(m: Model, target: String): JSONObject = group(
        "when", target, "When to sync",
        toggle(IN_WIFI_ONLY, "Sync only on Wi-Fi", "Or Ethernet. Mobile data is never used.", m.settings.wifiOnly, call(OP_SAVE)),
        toggle(
            IN_CHARGING_ONLY, "Sync only while charging",
            if (m.charging == null) "This handheld does not tell droidtop when it charges, so this has no effect." else null,
            m.settings.chargingOnly, call(OP_SAVE),
        ),
        facet(IN_RESCAN, "slider", null)
            .put("label", "Look for changes every").put("unit", "min")
            .put("min", SyncthingSettings.MIN_RESCAN_MINUTES).put("max", SyncthingSettings.MAX_RESCAN_MINUTES).put("step", 1)
            .put("value", m.settings.rescanMinutes).put("call", call(OP_SAVE)),
        info("rescan_note", null, "Changes are found by scanning", null, "Not by watching, which Android does not allow here."),
    )

    private fun foldersGroup(m: Model, target: String): JSONObject {
        val running = m.state.optBoolean("running")
        val folders = m.state.optJSONArray("folders").objects()
        val items = folders.mapIndexed { i, f ->
            action("folder_$i", null, f.optString("label").ifBlank { f.optString("id") }, folderLine(f, running), page(OP_FOLDER, JSONObject().put("id", f.optString("id"))))
        }.ifEmpty { listOf(info("no_folders", null, "No folders yet", null, "Add one below, or accept one a device offers.")) }
        return group("folders", target, "Folders", *items.toTypedArray())
    }

    fun folderLine(f: JSONObject, running: Boolean): String {
        val sharing = when (val shared = f.optJSONArray("devices")?.length() ?: 0) {
            0 -> "Not shared yet"
            1 -> "Shared with 1 device"
            else -> "Shared with $shared devices"
        }
        return if (running) "${stateLabel(f.optString("state"))} · $sharing" else sharing
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

    private fun pendingGroup(m: Model, target: String): JSONObject? {
        val pending = m.state.optJSONArray("pending").objects().filter { !it.optBoolean("encrypted") }
        if (pending.isEmpty()) return null
        val names = deviceNames(m.state)
        val items = pending.mapIndexed { i, p ->
            action(
                "pending_$i", null, p.optString("label").ifBlank { p.optString("id") },
                "Offered by ${names[p.optString("device")] ?: "a device"}. Accept to sync it here.",
                call(OP_ACCEPT_FOLDER, JSONObject().put("id", p.optString("id")).put("label", p.optString("label")).put("device", p.optString("device"))),
            )
        }
        return group("pending", target, "Offered to this device", *items.toTypedArray())
    }

    /**
     * The devices paired with droidtop. Without the `devices.paired` permission the group says so and asks for it in
     * place (a grant action), rather than sending the person to the plugin's Permissions.
     */
    fun pairedGroup(m: Model, target: String): JSONObject {
        val connected = connectedIds(m.state)
        val items = ArrayList<JSONObject>()
        m.pairingProblem?.let {
            items += facet("pairing", "notice", null).put("title", "Paired devices are not shared with Syncthing").put("sub", it.take(140))
                .put("tone", "caution").put("call", JSONObject().put("kind", "grant").put("permission", PAIRED_PERMISSION))
        }
        for ((i, d) in m.paired.withIndex()) {
            val value = when {
                d.syncthingId == null -> "No Syncthing yet"
                d.syncthingId in connected -> "Connected"
                m.state.optBoolean("running") -> "Not connected"
                else -> "Paired"
            }
            items += info("paired_$i", null, d.name, value, if (d.kind == "handheld") "Handheld" else "Computer")
        }
        if (m.paired.isEmpty() && m.pairingProblem == null) {
            items += info("no_paired", null, "No paired devices", null, "Pair a computer or another handheld with droidtop and it syncs here by itself.")
        }
        return group("paired", target, "Paired with droidtop", *items.toTypedArray())
    }

    private fun devicesGroup(m: Model, target: String): JSONObject {
        val connected = connectedIds(m.state)
        val running = m.state.optBoolean("running")
        val items = m.state.optJSONArray("devices").objects().filter { it.optString("id") !in m.settings.pairedIds }.mapIndexed { i, d ->
            val id = d.optString("id")
            val trust = if (d.optBoolean("untrusted")) "Untrusted: keeps an encrypted copy" else "Trusted"
            val link = if (id in connected) "Connected" else if (running) "Not connected" else null
            action("device_$i", null, d.optString("name").ifBlank { id.take(7) }, listOfNotNull(trust, link).joinToString(" · "), page(OP_DEVICE, JSONObject().put("id", id)))
        }.ifEmpty { listOf(info("no_devices", null, "No other devices", null, "Add any Syncthing below, by its device ID.")) }
        return group("others", target, "Other Syncthing devices", *items.toTypedArray())
    }

    /** "Add a folder": a form, so the name and the paired switch go together with Add, as one call. */
    fun addFolderForm(target: String): JSONObject = form(
        "add_folder", target, "Add a folder",
        submit("Add", "With no folder given, it is kept in this plugin's own storage", call(OP_ADD_FOLDER)),
        facet(IN_FOLDER_NAME, "input", null).put("label", "Name").put("hint", "Shown on every device it syncs with").put("value", ""),
        facet(IN_FOLDER_PATH, "input", null).put("label", "Folder on this device").put("optional", true).put("value", "")
            .put("hint", "Synced in place, such as /storage/emulated/0/Saves. Needs shared storage"),
        facet(IN_FOLDER_PAIRED, "toggle", null).put("label", "Share with my paired devices").put("value", true),
    )

    /** "Add a Syncthing device": its ID, a name and how far it is trusted, then Add. */
    fun addDeviceForm(target: String): JSONObject = form(
        "add_device", target, "Add a Syncthing device",
        submit("Add", null, call(OP_ADD_DEVICE)),
        facet(IN_DEVICE_ID, "input", null).put("label", "Device ID").put("hint", "From that Syncthing: its Actions, Show ID").put("value", ""),
        facet(IN_DEVICE_NAME, "input", null).put("label", "Name").put("value", "").put("optional", true),
        facet(IN_DEVICE_TRUST, "choice", null).put("label", "Trust")
            .put("sub", "An untrusted device, such as a backup server, never sees your files")
            .put("options", JSONArray(listOf(option(TRUST_UNTRUSTED, "Untrusted: keeps an encrypted copy"), option(TRUST_TRUSTED, "Trusted: sees the files"))))
            .put("value", TRUST_UNTRUSTED),
    )

    // -----------------------------------------------------------------
    // Pages a facet opens (kind page): replies of facets
    // -----------------------------------------------------------------

    fun folder(state: JSONObject, settings: SyncthingSettings, id: String): JSONObject {
        val f = state.optJSONArray("folders").objects().firstOrNull { it.optString("id") == id }
            ?: return reply("Folder", listOf(info("gone", null, "This folder is no longer synced", null)))
        val running = state.optBoolean("running")
        val label = f.optString("label").ifBlank { id }
        val shared = f.optJSONArray("devices").objects().map { it.optString("id") }.toSet()
        val out = arrayListOf(
            info("state", null, "State", if (running) stateLabel(f.optString("state")) else "Not running", f.optString("error").ifBlank { null }),
            info("size", null, "Size", JSONObject().put("number", f.optLong("globalBytes")).put("unit", "bytes"), "${f.optInt("globalFiles")} files"),
            info("need", null, "Still to fetch", JSONObject().put("number", f.optLong("needBytes")).put("unit", "bytes")),
            info("path", null, "Kept in", f.optString("path")),
            info("folder_id", null, "Folder ID", f.optString("id")),
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
                call(OP_SHARE, JSONObject().put("id", id).put("device", deviceId)),
            )
        }
        out += group("sharing", null, "Shared with", *sharing.toTypedArray())
        // The password is droidtop's to show, on its own sheet: never in a message line.
        if (untrustedShared) {
            out += facet("password", "secret", null).put("label", "Encryption password")
                .put("sub", "Needed to open the encrypted copy anywhere else").put("call", call(OP_SHOW_PASSWORD, JSONObject().put("id", id)))
        }
        out += action("remove", null, "Stop syncing this folder", "Its files stay on this handheld", call(OP_REMOVE_FOLDER, JSONObject().put("id", id)))
            .put("confirm", "danger")
        return reply(label, out)
    }

    fun device(state: JSONObject, id: String): JSONObject {
        val d = state.optJSONArray("devices").objects().firstOrNull { it.optString("id") == id }
            ?: return reply("Device", listOf(info("gone", null, "This device was removed", null)))
        val name = d.optString("name").ifBlank { id.take(7) }
        val untrusted = d.optBoolean("untrusted")
        val out = arrayListOf(
            info("id", null, "Device ID", id),
            info(
                "trust", null, "Trust", if (untrusted) "Untrusted" else "Trusted",
                if (untrusted) "It keeps an encrypted copy and never sees your files." else "It sees the files of the folders you share with it.",
            ),
            info("connected", null, "Connection", if (id in connectedIds(state)) "Connected" else "Not connected"),
        )
        val folders = state.optJSONArray("folders").objects().mapIndexed { i, f ->
            val on = f.optJSONArray("devices").objects().any { it.optString("id") == id }
            toggle("share_$i", f.optString("label").ifBlank { f.optString("id") }, null, on, call(OP_SHARE, JSONObject().put("id", f.optString("id")).put("device", id)))
        }.ifEmpty { listOf(info("none", null, "No folders yet", null)) }
        out += group("folders", null, "Folders shared with it", *folders.toTypedArray())
        out += action("remove", null, "Remove this device", "It stops syncing with this handheld", call(OP_REMOVE_DEVICE, JSONObject().put("id", id)))
            .put("confirm", "danger")
        return reply(name, out)
    }

    fun log(text: String): JSONObject {
        val lines = text.lines().filter { it.isNotBlank() }.takeLast(40)
        val body = if (lines.isEmpty()) listOf(info("empty", null, "Nothing logged yet", null))
        else listOf(facet("log", "text", null).put("body", lines.joinToString("\n")).put("format", "plain"))
        return reply("Syncthing's log", body)
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun connectedIds(state: JSONObject): Set<String> =
        state.optJSONArray("devices").objects().filter { it.optBoolean("connected") }.map { it.optString("id") }.toSet()

    private fun deviceNames(state: JSONObject): Map<String, String> =
        state.optJSONArray("devices").objects().associate { it.optString("id") to it.optString("name").ifBlank { it.optString("id").take(7) } }

    private fun reply(title: String?, facets: List<JSONObject>): JSONObject =
        JSONObject().apply { title?.let { put("title", it) } }.put("facets", JSONArray(facets))

    /** A facet's envelope; [target] is null for one inside a group or on a page, which takes its place from there. */
    private fun facet(id: String, kind: String, target: String?): JSONObject =
        JSONObject().put("id", id).put("kind", kind).put("v", 1).apply { target?.let { put("targets", JSONArray(listOf(it))) } }

    private fun info(id: String, target: String?, label: String, value: Any?, sub: String? = null): JSONObject =
        facet(id, "info", target).put("label", label).apply { value?.let { put("value", it) } }.apply { sub?.let { put("sub", it) } }

    private fun action(id: String, target: String?, label: String, sub: String?, call: JSONObject): JSONObject =
        facet(id, "action", target).put("label", label).put("call", call).apply { sub?.let { put("sub", it) } }

    private fun toggle(id: String, label: String, sub: String?, value: Boolean, call: JSONObject): JSONObject =
        facet(id, "toggle", null).put("label", label).put("value", value).put("call", call).apply { sub?.let { put("sub", it) } }

    private fun notice(id: String, target: String, title: String, sub: String, tone: String): JSONObject =
        facet(id, "notice", target).put("title", title).put("sub", sub.take(140)).put("tone", tone)

    private fun group(id: String, target: String?, title: String?, vararg facets: JSONObject): JSONObject =
        facet(id, "group", target).apply { title?.let { put("title", it) } }.put("facets", JSONArray(facets.toList()))

    private fun form(id: String, target: String, title: String, submit: JSONObject, vararg inputs: JSONObject): JSONObject =
        group(id, target, title, *inputs).put("form", true).put("submit", submit)

    private fun submit(label: String, sub: String?, call: JSONObject): JSONObject =
        JSONObject().put("label", label).put("call", call).apply { sub?.let { put("sub", it) } }

    private fun option(value: String, label: String): JSONObject = JSONObject().put("value", value).put("label", label)

    private fun call(op: String, args: JSONObject = JSONObject()): JSONObject = JSONObject().put("op", op).put("args", args)

    private fun page(op: String, args: JSONObject = JSONObject()): JSONObject = JSONObject().put("op", op).put("kind", "page").put("args", args)
}
