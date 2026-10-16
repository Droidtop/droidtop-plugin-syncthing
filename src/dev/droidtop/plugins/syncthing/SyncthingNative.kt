package dev.droidtop.plugins.syncthing

import org.json.JSONObject

/**
 * The one call into upstream Syncthing, built as `libsyncthing.so` from `go/droidtopsyncthing` (README.md). JSON in
 * and out as UTF-8 bytes; the reply is `{"ok": true, "data": {...}}` or `{"ok": false, "error": "..."}`, turned here
 * into a [JSONObject] or a [SyncthingException].
 */
internal object SyncthingNative {
    @Volatile private var loaded = false

    @JvmStatic private external fun call(op: ByteArray, args: ByteArray): ByteArray

    /** Loads the library once. droidtop's guarded loader maps it from the plugin's payload (docs/plugin-api.md 5.3). */
    @Synchronized
    fun load() {
        if (!loaded) {
            System.loadLibrary("syncthing")
            loaded = true
        }
    }

    fun op(op: String, args: JSONObject = JSONObject()): JSONObject {
        load()
        val raw = call(op.toByteArray(Charsets.UTF_8), args.toString().toByteArray(Charsets.UTF_8))
        val reply = runCatching { JSONObject(raw.toString(Charsets.UTF_8)) }.getOrNull()
            ?: throw SyncthingException("Syncthing gave no answer to $op")
        if (!reply.optBoolean("ok")) throw SyncthingException(reply.optString("error").ifBlank { "$op failed" })
        return reply.optJSONObject("data") ?: JSONObject()
    }
}

internal class SyncthingException(message: String) : Exception(message)
