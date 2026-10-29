package dev.droidtop.plugins.syncthing

import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginEvent
import dev.droidtop.pluginhost.PluginJobProgress
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginResult
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * droidtop's Syncthing (Droidtop/tracker#553): a full upstream Syncthing that keeps droidtop instances, droidtop-agent
 * and Pythia in step, paired through droidtop's own device pairing, and can also sync with any other Syncthing the
 * person adds, trusted or untrusted (an encrypted copy, for backups).
 *
 * It runs in an open process with the `net.sockets` hole only (docs/plugin-api.md 5.3, "Open processes"): its own
 * internet sockets and nothing else extra. Syncthing itself is a background service (`jobs.service`, E8) that droidtop
 * starts and stops; what a person sees of it are facets (`ui.facets`, docs/plugin-api.md 1.10) from [SyncthingFacets],
 * drawn by droidtop on the plugin's page, its settings page and the Quick Menu.
 */
class SyncthingPlugin : DroidtopPlugin {
    private lateinit var context: PluginContext
    private lateinit var engine: SyncthingEngine
    private val service = Service()

    override fun onLoad(context: PluginContext) {
        this.context = context
        this.engine = SyncthingEngine(context)
    }

    override fun onUnload() {
        service.cancelAll()
        runCatching { SyncthingNative.op("stop") }
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult =
        PluginResult.failure("droidtop's Syncthing has no ${capability.id}")

    override fun handle(call: PluginCall): PluginReply = when (call.point) {
        POINT_FACETS -> try {
            page(call)
        } catch (e: SyncthingException) {
            PluginReply.failed(PluginErrorCode.FAILED, "Syncthing could not do that.", e.message)
        } catch (e: SyncthingEngine.HostRefusal) {
            PluginReply.failed(PluginErrorCode.FAILED, "droidtop did not allow that.", e.message)
        } catch (e: Exception) {
            PluginReply.failed(PluginErrorCode.FAILED, "That did not work.", e.message ?: e.javaClass.simpleName)
        } catch (e: LinkageError) {
            PluginReply.failed(PluginErrorCode.FAILED, "Syncthing could not be loaded on this device.", e.message)
        }
        else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Nothing here for ${call.point}")
    }

    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        val call = args.string("call")?.let { PluginCall.fromJson(it) }
        if (call == null || call.point != POINT_SERVICE || call.op != "run" || call.args.optString("serviceId") != SERVICE_ID) {
            throw UnsupportedOperationException("droidtop's Syncthing runs only its '$SERVICE_ID' service as a job")
        }
        service.start(jobId, progress)
    }

    override fun cancelJob(jobId: String) = service.cancel(jobId)

    override fun onEvent(event: PluginEvent, args: PluginArgs): PluginResult {
        if (event == PluginEvent.DEVICES_CHANGED) service.wake()
        return PluginResult.success()
    }

    // -----------------------------------------------------------------
    // The background service: Syncthing runs while it is allowed to
    // -----------------------------------------------------------------

    /**
     * The `syncthing` service job. Every [POLL_MS] (and at once when the paired devices or a setting change) it reads
     * droidtop's networks, keeps the DNS servers current, starts or stops Syncthing by the person's Wi-Fi and charging
     * choices, and accepts exactly the devices droidtop paired. Three failures in a row end the job with the reason,
     * and droidtop's own restart schedule takes over (E8).
     */
    private inner class Service {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var jobId: String? = null
        private var woken = false

        fun start(id: String, progress: PluginJobProgress) {
            lock.withLock {
                jobId = id
                changed.signalAll()
            }
            Thread({ run(id, progress) }, "syncthing-service").apply { isDaemon = true }.start()
        }

        fun cancel(id: String) {
            lock.withLock {
                if (jobId == id) jobId = null
                changed.signalAll()
            }
        }

        fun cancelAll() {
            lock.withLock {
                jobId = null
                changed.signalAll()
            }
        }

        fun wake() {
            lock.withLock {
                woken = true
                changed.signalAll()
            }
        }

        private fun current(id: String) = lock.withLock { jobId == id }

        private fun pause() {
            lock.withLock {
                if (!woken) changed.await(POLL_MS, TimeUnit.MILLISECONDS)
                woken = false
            }
        }

        private fun run(id: String, progress: PluginJobProgress) {
            engine.serviceOn = true
            var failures = 0
            var result = PluginResult.success(mapOf("message" to "Sync stopped"))
            try {
                while (current(id)) {
                    val links = runCatching { engine.host("net", "get_links") }.getOrNull()
                    try {
                        engine.ready(links)
                        links?.optJSONArray("dns")?.let { SyncthingNative.op("set_dns", JSONObject().put("dns", it)) }
                        val waiting = engine.gate(links, engine.settings.read())
                        engine.waitingFor = waiting
                        if (waiting == null) SyncthingNative.op("start") else SyncthingNative.op("stop")
                        engine.reconcile(links)
                        engine.problem = null
                        failures = 0
                        progress.report(-1, waiting ?: statusLine())
                    } catch (e: Throwable) {
                        if (e is VirtualMachineError) throw e
                        failures++
                        engine.problem = e.message ?: e.javaClass.simpleName
                        progress.report(-1, "Syncthing: ${engine.problem}")
                        if (failures >= MAX_FAILURES) {
                            result = PluginResult.failure(engine.problem ?: "Syncthing failed")
                            break
                        }
                    }
                    if (current(id)) pause()
                }
            } finally {
                // A newer run of the service may already own Syncthing; only the last one stops it.
                val last = lock.withLock {
                    if (jobId == id) jobId = null
                    jobId == null
                }
                if (last) {
                    runCatching { SyncthingNative.op("stop") }
                    engine.serviceOn = false
                    engine.waitingFor = null
                }
                progress.complete(result)
            }
        }

        private fun statusLine(): String {
            val state = engine.state()
            val connected = state.optJSONArray("devices").objects().count { it.optBoolean("connected") }
            val syncing = state.optJSONArray("folders").objects().count { it.optString("state").startsWith("sync") }
            return when {
                syncing > 0 -> "Syncing $syncing ${if (syncing == 1) "folder" else "folders"}"
                connected == 0 -> "Running, no device connected"
                connected == 1 -> "Up to date with 1 device"
                else -> "Up to date with $connected devices"
            }
        }
    }

    // -----------------------------------------------------------------
    // The pages
    // -----------------------------------------------------------------

    private fun page(call: PluginCall): PluginReply {
        val args = call.args
        val values = args.optJSONObject("values") ?: JSONObject()
        return when (call.op) {
            SyncthingFacets.OP_GET -> PluginReply.ok(SyncthingFacets.forTarget(args.optString("target"), model()))
            SyncthingFacets.OP_FOLDER -> withState { SyncthingFacets.folder(it, engine.settings.read(), args.optString("id")) }
            SyncthingFacets.OP_DEVICE -> withState { SyncthingFacets.device(it, args.optString("id")) }
            SyncthingFacets.OP_LOG -> {
                val text = runCatching { engine.ready(); SyncthingNative.op("read_log", JSONObject().put("bytes", 16384)).optString("text") }.getOrDefault("")
                PluginReply.ok(SyncthingFacets.log(text))
            }
            SyncthingFacets.OP_SAVE -> save(values)
            SyncthingFacets.OP_SCAN -> {
                engine.ready()
                SyncthingNative.op("scan")
                message("Looking for changes in every folder")
            }
            SyncthingFacets.OP_ADD_FOLDER -> addFolder(values)
            SyncthingFacets.OP_REMOVE_FOLDER -> {
                engine.ready()
                val id = args.optString("id")
                SyncthingNative.op("delete_folder", JSONObject().put("id", id))
                engine.settings.update { it.copy(sharedWithPaired = it.sharedWithPaired - id) }
                message("No longer synced. Its files stay here.")
            }
            // A switch sends its own new state as `args.value` (docs/plugin-api.md 1.10, "Interaction").
            SyncthingFacets.OP_SHARE_PAIRED -> sharePaired(args.optString("id"), args.optBoolean("value"))
            SyncthingFacets.OP_SHARE -> share(args.optString("id"), args.optString("device"), args.optBoolean("value"))
            SyncthingFacets.OP_ADD_DEVICE -> addDevice(values)
            SyncthingFacets.OP_REMOVE_DEVICE -> {
                engine.ready()
                SyncthingNative.op("delete_device", JSONObject().put("id", args.optString("id")))
                message("Removed")
            }
            SyncthingFacets.OP_ACCEPT_FOLDER -> {
                engine.ready()
                val state = engine.state()
                val label = args.optString("label").ifBlank { args.optString("id") }
                engine.putFolder(args.optString("id"), label, setOf(args.optString("device")), engine.untrustedIds(state))
                message("Syncing $label")
            }
            // A secret facet's Show: droidtop puts the value on its own sheet and never keeps it.
            SyncthingFacets.OP_SHOW_PASSWORD -> {
                engine.ready()
                PluginReply.ok(JSONObject().put("value", engine.folderPassword(args.optString("id"))))
            }
            else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Unknown page action: ${call.op}")
        }
    }

    private fun model(): SyncthingFacets.Model {
        val problem = engine.problem
        val state = try {
            engine.ready()
            engine.state()
        } catch (e: LinkageError) {
            return SyncthingFacets.Model(JSONObject(), engine.settings.read(), engine.waitingFor, engine.serviceOn, engine.charging(), engine.paired, engine.pairingProblem, "Syncthing could not be loaded on this device: ${e.message}")
        } catch (e: Exception) {
            return SyncthingFacets.Model(JSONObject(), engine.settings.read(), engine.waitingFor, engine.serviceOn, engine.charging(), engine.paired, engine.pairingProblem, e.message ?: problem)
        }
        if (!engine.serviceOn) {
            // Without the service nothing has read the paired devices in this process yet; the page still lists them.
            runCatching { engine.reconcile(runCatching { engine.host("net", "get_links") }.getOrNull()) }
        }
        return SyncthingFacets.Model(state, engine.settings.read(), engine.waitingFor, engine.serviceOn, engine.charging(), engine.paired, engine.pairingProblem, problem)
    }

    private fun withState(build: (JSONObject) -> JSONObject): PluginReply {
        engine.ready()
        return PluginReply.ok(build(engine.state()))
    }

    private fun save(values: JSONObject): PluginReply {
        fun flag(key: String, current: Boolean) = when (values.optString(key)) {
            "true" -> true
            "false" -> false
            else -> current
        }
        val before = engine.settings.read()
        val after = engine.settings.update {
            it.copy(
                wifiOnly = flag(SyncthingFacets.IN_WIFI_ONLY, it.wifiOnly),
                chargingOnly = flag(SyncthingFacets.IN_CHARGING_ONLY, it.chargingOnly),
                // A slider's value may come as "15" or as 15.0.
                rescanMinutes = values.optString(SyncthingFacets.IN_RESCAN).toDoubleOrNull()?.toInt()
                    ?.coerceIn(SyncthingSettings.MIN_RESCAN_MINUTES, SyncthingSettings.MAX_RESCAN_MINUTES) ?: it.rescanMinutes,
            )
        }
        if (after.rescanMinutes != before.rescanMinutes) {
            engine.ready()
            SyncthingNative.op("set_rescan", JSONObject().put("seconds", after.rescanMinutes * 60))
        }
        service.wake()
        return message("Saved")
    }

    private fun addFolder(values: JSONObject): PluginReply {
        val name = values.optString(SyncthingFacets.IN_FOLDER_NAME).trim()
        if (name.isEmpty()) return PluginReply.error(PluginErrorCode.INVALID_ARGS, "Give the folder a name")
        val withPaired = values.optString(SyncthingFacets.IN_FOLDER_PAIRED, "true") != "false"
        engine.ready()
        val state = engine.state()
        val known = state.optJSONArray("devices").objects().map { it.optString("id") }.toSet()
        val id = newFolderId()
        val devices = if (withPaired) engine.settings.read().pairedIds.intersect(known) else emptySet()
        engine.putFolder(id, name, devices, engine.untrustedIds(state))
        if (withPaired) engine.settings.update { it.copy(sharedWithPaired = it.sharedWithPaired + id) }
        return message("Added $name")
    }

    private fun sharePaired(id: String, on: Boolean) = editFolder(id) { current, settings ->
        engine.settings.update { it.copy(sharedWithPaired = if (on) it.sharedWithPaired + id else it.sharedWithPaired - id) }
        if (on) current + settings.pairedIds else current - settings.pairedIds
    }

    private fun share(id: String, device: String, on: Boolean) = editFolder(id) { current, _ ->
        if (on) current + device else current - device
    }

    /** Changes the devices one folder is shared with. */
    private fun editFolder(id: String, change: (Set<String>, SyncthingSettings) -> Set<String>): PluginReply {
        engine.ready()
        val state = engine.state()
        val folder = state.optJSONArray("folders").objects().firstOrNull { it.optString("id") == id }
            ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "That folder is no longer synced")
        val known = state.optJSONArray("devices").objects().map { it.optString("id") }.toSet()
        val current = folder.optJSONArray("devices").objects().map { it.optString("id") }.toSet()
        val wanted = change(current, engine.settings.read()).intersect(known)
        engine.putFolder(id, folder.optString("label"), wanted, engine.untrustedIds(state))
        return message("Saved")
    }

    private fun addDevice(values: JSONObject): PluginReply {
        val id = values.optString(SyncthingFacets.IN_DEVICE_ID).trim().uppercase()
        if (id.isEmpty()) return PluginReply.error(PluginErrorCode.INVALID_ARGS, "Enter the device's Syncthing ID")
        val name = values.optString(SyncthingFacets.IN_DEVICE_NAME).trim().ifBlank { "Syncthing device" }
        val untrusted = values.optString(SyncthingFacets.IN_DEVICE_TRUST, SyncthingFacets.TRUST_UNTRUSTED) != SyncthingFacets.TRUST_TRUSTED
        engine.ready()
        try {
            SyncthingNative.op(
                "put_device",
                JSONObject().put("id", id).put("name", name).put("untrusted", untrusted).put("autoAccept", false),
            )
        } catch (e: SyncthingException) {
            return PluginReply.error(PluginErrorCode.INVALID_ARGS, "That is not a Syncthing device ID")
        }
        service.wake()
        return message("Added $name. Share folders with it from its page.")
    }

    private fun newFolderId(): String {
        val alphabet = "abcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        val part = { (1..5).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
        return "${part()}-${part()}"
    }

    private fun message(text: String): PluginReply = PluginReply.ok(JSONObject().put("message", text))

    companion object {
        private const val POINT_FACETS = "ui.facets"
        private const val POINT_SERVICE = "jobs.service"
        const val SERVICE_ID = "syncthing"
        private const val POLL_MS = 30_000L
        private const val MAX_FAILURES = 3
    }
}
