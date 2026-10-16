package dev.droidtop.plugins.syncthing

import org.json.JSONArray
import org.json.JSONObject

/** The objects of a JSON array, skipping anything else; empty for null. */
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** The `id` of each object in a JSON array. */
internal fun JSONArray?.ids(): Set<String> = objects().mapNotNull { it.optString("id").ifBlank { null } }.toSet()
