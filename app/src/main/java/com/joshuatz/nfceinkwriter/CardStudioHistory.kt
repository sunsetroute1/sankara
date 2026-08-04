package com.joshuatz.nfceinkwriter

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CardStudioHistoryEntry(
    val id: String,
    val createdAtMs: Long,
    val snapshot: CardStudioSnapshot,
) {
    fun displayLabel(): String = snapshot.displayLabel()
}

class CardStudioHistory(private val context: Context) {
    private val dir: File = File(context.filesDir, "card_studio_history").also { it.mkdirs() }
    private val indexFile = File(dir, "index.json")

    fun listEntries(): List<CardStudioHistoryEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val array = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        CardStudioHistoryEntry(
                            id = obj.getString("id"),
                            createdAtMs = obj.getLong("createdAtMs"),
                            snapshot = CardStudioSnapshot.fromJson(obj.getJSONObject("snapshot")),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun thumbnailFile(entryId: String): File = File(dir, "$entryId.png")

    fun addEntry(snapshot: CardStudioSnapshot, thumbnail: Bitmap): CardStudioHistoryEntry {
        val existing = listEntries().firstOrNull { it.snapshot == snapshot }
        val entry = if (existing != null) {
            thumbnailFile(existing.id).outputStream().use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            existing.copy(createdAtMs = System.currentTimeMillis())
        } else {
            val newEntry = CardStudioHistoryEntry(
                id = UUID.randomUUID().toString(),
                createdAtMs = System.currentTimeMillis(),
                snapshot = snapshot,
            )
            thumbnailFile(newEntry.id).outputStream().use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            newEntry
        }
        val others = listEntries().filter { it.id != entry.id }
        persist((listOf(entry) + others).take(MAX_ENTRIES))
        return entry
    }

    fun deleteEntry(entryId: String) {
        thumbnailFile(entryId).delete()
        persist(listEntries().filter { it.id != entryId })
    }

    fun findEntry(entryId: String): CardStudioHistoryEntry? =
        listEntries().firstOrNull { it.id == entryId }

    private fun persist(entries: List<CardStudioHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("createdAtMs", entry.createdAtMs)
                    put("snapshot", entry.snapshot.toJson())
                },
            )
        }
        indexFile.writeText(array.toString())
        val keepIds = entries.map { it.id }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.extension == "png" && file.nameWithoutExtension !in keepIds) {
                file.delete()
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 20
    }
}
