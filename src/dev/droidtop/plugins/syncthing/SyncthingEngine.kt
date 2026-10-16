package dev.droidtop.plugins.syncthing

import dev.droidtop.pluginhost.PluginContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * droidtop's side of the one Syncthing this plugin process runs (README.md, "How it fits together"): where it lives,
 * its identity in the vault, when it may run (the person's Wi-Fi and charging choices), and which devices it accepts.
 *
 * Pairing is droidtop's (docs/plugin-api.md D21, Droidtop/tracker#553): this device announces its Syncthing ID and
 * LAN addresses to every device the person paired with droidtop (`devices.announce`), reads what the same plugin
 * announced on each of them (`devices.list_paired`), and accepts exactly those IDs, with their folders accepted
 * automatically. Syncthing's own pairing is never shown. Other Syncthing devices are the person's to add by ID, trusted
 * or untrusted (an untrusted one keeps only an encrypted copy).
 */
internal class SyncthingEngine(private val context: PluginContext) {
    val home: File = File(dataRoot(), "files/syncthing")
    val settings = SettingsStore(File(home, "droidtop-settings.json"))

    @Volatile var deviceId: String? = null
        private set

    /** Why sync is not running now, in the person's words, or null while it runs. Set by the service loop. */
    @Volatile var waitingFor: String? = null

    /** Whether droidtop runs the background service (the switch on the plugin's Permissions screen). */
    @Volatile var serviceOn: Boolean = false

    /** Why the service could not run Syncthing the last time it tried, for the page; null when it could. */
    @Volatile var problem: String? = null

    /** The last pairing problem, for the page: null when the paired devices were read. */
    @Volatile var pairingProblem: String? = null

    /** The paired devices as droidtop last listed them, for the page. */
    @Volatile var paired: List<PairedDevice> = emptyList()

    data class PairedDevice(val name: String, val kind: String, val online: Boolean, val syncthingId: String?)

    // -----------------------------------------------------------------
    // droidtop's broker
    // -----------------------------------------------------------------

    /** One broker call; the reply's `data`, or a [HostRefusal] with droidtop's code and sentence. */
    fun host(api: String, op: String, args: JSONObject = JSONObject()): JSONObject {
        val reply = runCatching { JSONObject(context.call(api, 1, op, args.toString())) }.getOrNull()
            ?: throw HostRefusal("FAILED", "droidtop gave no answer to $api.$op")
        if (reply.optBoolean("ok")) return reply.optJSONObject("data") ?: JSONObject()
        val error = reply.optJSONObject("error")
        throw HostRefusal(error?.optString("code").orEmpty().ifBlank { "FAILED" }, error?.optString("message").orEmpty().ifBlank { "$api.$op was refused" })
    }

    class HostRefusal(val code: String, message: String) : Exception(message)

    // -----------------------------------------------------------------
    // Getting Syncthing ready: home, DNS, identity, configuration
    // -----------------------------------------------------------------

    /** Loads Syncthing and its configuration once; safe to call from any page or the service. Answers the device ID. */
    @Synchronized
    fun ready(links: JSONObject? = null): String {
        deviceId?.let { return it }
        val net = links ?: runCatching { host("net", "get_links") }.getOrNull()
        SyncthingNative.op("configure", JSONObject().put("home", home.path).put("dns", net?.optJSONArray("dns") ?: JSONArray()))
        val cert = vaultGet(VAULT_CERT)
        val key = vaultGet(VAULT_KEY)
        val identity = SyncthingNative.op("load_identity", JSONObject().put("cert", cert.orEmpty()).put("key", key.orEmpty()))
        if (cert == null || key == null || identity.optBoolean("generated")) {
            // A new identity: it is this device's Syncthing ID from now on, so it is kept before it is used.
            vaultPut(VAULT_CERT, identity.getString("cert"))
            vaultPut(VAULT_KEY, identity.getString("key"))
        }
        val id = identity.getString("deviceId")
        val chosen = settings.read()
        SyncthingNative.op("set_rescan", JSONObject().put("seconds", chosen.rescanMinutes * 60))
        if (!chosen.defaultFolderMade) {
            putFolder(DEFAULT_FOLDER_ID, DEFAULT_FOLDER_LABEL, emptySet(), emptySet())
            settings.update { it.copy(defaultFolderMade = true, sharedWithPaired = it.sharedWithPaired + DEFAULT_FOLDER_ID) }
        }
        deviceId = id
        return id
    }

    fun state(): JSONObject = SyncthingNative.op("get_state")

    private fun vaultGet(key: String): String? =
        host("vault", "get", JSONObject().put("key", key)).let { if (it.isNull("value")) null else it.optString("value").ifBlank { null } }

    private fun vaultPut(key: String, value: String) {
        host("vault", "put", JSONObject().put("key", key).put("value", value))
    }

    // -----------------------------------------------------------------
    // When to sync
    // -----------------------------------------------------------------

    /** Null when sync may run now; otherwise what it waits for. */
    fun gate(links: JSONObject?, chosen: SyncthingSettings): String? {
        if (chosen.wifiOnly && !onLan(links)) return "Waiting for Wi-Fi"
        if (chosen.chargingOnly && charging() == false) return "Waiting for the charger"
        return null
    }

    /** True when the active network is Wi-Fi or Ethernet, or a VPN over one of them. */
    private fun onLan(links: JSONObject?): Boolean {
        val all = links?.optJSONArray("links") ?: return false
        val list = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
        val active = list.firstOrNull { it.optBoolean("active") } ?: return false
        return when (active.optString("type")) {
            "wifi", "ethernet" -> true
            "vpn" -> list.any { it.optString("type") in LAN_TYPES }
            else -> false
        }
    }

    /**
     * Whether the handheld charges, from the kernel's power supply files; null when this device does not let droidtop
     * read them (then "Only while charging" cannot be kept, and the page says so). droidtop has no battery API for
     * plugins yet (docs/plugin-api.md D7); when it does, that replaces this.
     */
    fun charging(): Boolean? {
        readLine("/sys/class/power_supply/battery/status")?.let { return it.equals("Charging", true) || it.equals("Full", true) }
        var known = false
        for (supply in listOf("ac", "usb", "wireless", "dock")) {
            val online = readLine("/sys/class/power_supply/$supply/online") ?: continue
            known = true
            if (online == "1") return true
        }
        return if (known) false else null
    }

    private fun readLine(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.ifBlank { null }

    // -----------------------------------------------------------------
    // Pairing through droidtop
    // -----------------------------------------------------------------

    /**
     * Announces this device to the paired devices and accepts their Syncthing IDs; shares the folders marked for
     * paired devices with all of them. Needs `devices.paired`; without it only the person's own devices sync.
     */
    fun reconcile(links: JSONObject?) {
        val id = deviceId ?: return
        val state = state()
        val listed = try {
            host("devices", "list_paired").optJSONArray("devices") ?: JSONArray()
        } catch (e: HostRefusal) {
            pairingProblem = if (e.code == "PERMISSION_DENIED") "Allow \"See and reach the devices you paired\" to sync with them" else e.message
            return
        }
        pairingProblem = null
        val devices = (0 until listed.length()).mapNotNull { listed.optJSONObject(it) }
        announce(id, links, state.optInt("listenPort", 22000), devices.map { it.optString("id") }.sorted())
        val peers = LinkedHashMap<String, JSONObject>()
        paired = devices.map { device ->
            val announced = device.optJSONObject("announced")
            val peerId = announced?.optString("syncthing")?.trim()?.uppercase()?.takeIf { ID.matches(it) && it != id }
            if (peerId != null) peers[peerId] = device
            PairedDevice(device.optString("name").ifBlank { "Paired device" }, device.optString("kind"), device.optBoolean("online"), peerId)
        }
        for ((peerId, device) in peers) {
            val addresses = JSONArray()
            val offered = device.optJSONObject("announced")?.optJSONArray("addresses")
            if (offered != null) {
                for (i in 0 until minOf(offered.length(), MAX_ADDRESSES)) {
                    offered.optString(i).takeIf { ADDRESS.matches(it) }?.let { addresses.put(it) }
                }
            }
            addresses.put("dynamic")
            SyncthingNative.op(
                "put_device",
                JSONObject().put("id", peerId).put("name", device.optString("name").ifBlank { "Paired device" })
                    .put("addresses", addresses).put("untrusted", false).put("autoAccept", true),
            )
        }
        val chosen = settings.read()
        val gone = chosen.pairedIds - peers.keys
        val known = state.optJSONArray("devices").ids()
        gone.filter { it in known }.forEach { SyncthingNative.op("delete_device", JSONObject().put("id", it)) }
        settings.update { it.copy(pairedIds = peers.keys.toSet()) }
        shareWithPaired(peers.keys, gone)
    }

    /** Makes every folder marked for paired devices shared with exactly the paired devices, keeping the others. */
    private fun shareWithPaired(pairedNow: Set<String>, gone: Set<String>) {
        val chosen = settings.read()
        val state = state()
        val untrusted = untrustedIds(state)
        val folders = state.optJSONArray("folders") ?: return
        for (i in 0 until folders.length()) {
            val folder = folders.optJSONObject(i) ?: continue
            val folderId = folder.optString("id")
            if (folderId !in chosen.sharedWithPaired) continue
            val current = folder.optJSONArray("devices").ids()
            val wanted = (current - gone) + pairedNow
            if (wanted != current) putFolder(folderId, folder.optString("label"), wanted, untrusted)
        }
    }

    /**
     * `{syncthing, addresses}` to every paired device, when it or the set of paired devices changed: their plugin then
     * accepts and dials this one. A newly paired device gets it at once rather than at the next change.
     */
    private fun announce(id: String, links: JSONObject?, port: Int, pairedDeviceIds: List<String>) {
        val value = JSONObject().put("syncthing", id).put("addresses", JSONArray(lanAddresses(links, port)))
        val text = value.toString() + "|" + pairedDeviceIds.joinToString(",")
        if (settings.read().announced == text) return
        try {
            host("devices", "announce", JSONObject().put("value", value))
            settings.update { it.copy(announced = text) }
        } catch (e: HostRefusal) {
            pairingProblem = if (e.code == "PERMISSION_DENIED") "Allow \"See and reach the devices you paired\" to sync with them" else e.message
        }
    }

    /** This device's Syncthing addresses on its Wi-Fi and Ethernet networks (no loopback, no link-local). */
    private fun lanAddresses(links: JSONObject?, port: Int): List<String> {
        val all = links?.optJSONArray("links") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until all.length()) {
            val link = all.optJSONObject(i) ?: continue
            if (link.optString("type") !in LAN_TYPES) continue
            val addresses = link.optJSONArray("addresses") ?: continue
            for (j in 0 until addresses.length()) {
                val address = addresses.optJSONObject(j)?.optString("address")?.trim().orEmpty()
                if (address.isEmpty() || address.startsWith("127.") || address == "::1" || address.lowercase().startsWith("fe80") || '%' in address) continue
                val host = if (':' in address) "[$address]" else address
                out += "tcp://$host:$port"
                out += "quic://$host:$port"
            }
        }
        return out.take(MAX_ADDRESSES)
    }

    // -----------------------------------------------------------------
    // Folders and the person's own devices
    // -----------------------------------------------------------------

    fun untrustedIds(state: JSONObject): Set<String> {
        val devices = state.optJSONArray("devices") ?: return emptySet()
        return (0 until devices.length()).mapNotNull { devices.optJSONObject(it) }.filter { it.optBoolean("untrusted") }.map { it.optString("id") }.toSet()
    }

    /** Puts a folder shared with [devices]; an untrusted one gets it encrypted with the folder's own password. */
    fun putFolder(id: String, label: String, devices: Set<String>, untrusted: Set<String>, path: String? = null) {
        val list = JSONArray()
        for (device in devices.sorted()) {
            val entry = JSONObject().put("id", device)
            if (device in untrusted) entry.put("password", folderPassword(id))
            list.put(entry)
        }
        val args = JSONObject().put("id", id).put("label", label).put("devices", list)
        if (path != null) args.put("path", path)
        SyncthingNative.op("put_folder", args)
    }

    /** The folder's encryption password for untrusted devices: made once, kept in the vault, never in a backup. */
    fun folderPassword(folderId: String): String {
        val key = "folderpw." + sha256Hex(folderId).take(32)
        vaultGet(key)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val password = bytes.joinToString("") { "%02x".format(it) }
        vaultPut(key, password)
        return password
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val VAULT_CERT = "identity.cert"
        const val VAULT_KEY = "identity.key"

        /** The folder every droidtop shares with its paired devices: the same id everywhere, so they meet in it. */
        const val DEFAULT_FOLDER_ID = "droidtop"
        const val DEFAULT_FOLDER_LABEL = "droidtop"

        val LAN_TYPES = setOf("wifi", "ethernet")
        private const val MAX_ADDRESSES = 16

        /** A Syncthing device ID: eight groups of seven base32 characters. */
        val ID = Regex("^[A-Z2-7]{7}(-[A-Z2-7]{7}){7}$")
        private val ADDRESS = Regex("^(tcp|quic)://[0-9A-Za-z.:\\[\\]-]{1,80}:[0-9]{1,5}$")

        /**
         * droidtop's private folder as this process names it. droidtop's broker maps that name to the plugin's own
         * folder (docs/plugin-api.md 5.3), so this is the plugin's folder, never droidtop's files.
         */
        fun dataRoot(): File {
            val name = runCatching { File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8) }
                .getOrDefault("").substringBefore(':').trim()
            val user = android.os.Process.myUid() / 100_000
            return File("/data/user/$user/$name")
        }
    }
}
