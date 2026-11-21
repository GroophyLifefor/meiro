package com.muratkirazkaya.meiro.data.db

import com.muratkirazkaya.meiro.data.DeletedList
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LibraryFile
import com.muratkirazkaya.meiro.data.Persistence
import com.muratkirazkaya.meiro.data.Rfc3339Nano
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.data.SyncSettings
import com.muratkirazkaya.meiro.data.Tombstone
import com.muratkirazkaya.meiro.sync.SyncJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

/**
 * Room implementation of [Persistence]. Every save replaces what the stores hold in one transaction, so a crash
 * leaves either the old state or the new one. Call it from a background thread (the engine does).
 */
class RoomPersistence(private val db: MeiroDatabase) : Persistence {
    private val dao = db.dao()

    @Serializable private data class SourceRefDto(val source: String, val id: String = "", val url: String = "")

    private fun t(i: Instant) = Rfc3339Nano.format(i)
    private fun p(s: String) = Rfc3339Nano.parse(s)
    private fun tn(i: Instant?) = i?.let(::t)
    private fun pn(s: String?) = s?.let(::p)

    override fun loadLibraries(): LibraryFile = db.runInTransaction<LibraryFile> {
        val items = dao.items().groupBy { it.libraryId }
        val tombs = dao.tombstones().groupBy { it.libraryId }
        val libs = dao.libraries().map { l ->
            Library(
                id = l.id, name = l.name, createdAt = p(l.createdAt), serverId = l.serverId, updatedAt = p(l.updatedAt), cover = l.cover,
                items = items[l.id].orEmpty().map { i ->
                    Item(
                        source = i.source, key = i.key, url = i.url, title = i.title, artist = i.artist, durationMs = i.durationMs,
                        state = i.state, thumbnail = i.thumbnail, albumThumbnail = i.albumThumbnail, why = i.why, mbid = i.mbid,
                        artistMbid = i.artistMbid, artistState = i.artistState, addedAt = p(i.addedAt), updatedAt = p(i.updatedAt),
                        sources = SyncJson.decodeFromString(ListSerializer(SourceRefDto.serializer()), i.sourcesJson).map { SourceRef(it.source, it.id, it.url) },
                    )
                },
                removed = tombs[l.id].orEmpty().map { Tombstone(it.source, it.key, it.url, it.title, it.artist, p(it.removedAt)) },
            )
        }
        val deleted = dao.deletedLists().map { DeletedList(it.id, it.serverId, it.name, p(it.deletedAt)) }
        LibraryFile(libs, deleted)
    }

    override fun saveLibraries(file: LibraryFile) {
        db.runInTransaction {
            dao.clearLibraries() // items and tombstones go with their list (cascade)
            dao.clearDeletedLists()
            dao.insertLibraries(file.libraries.mapIndexed { n, l ->
                LibraryRow(l.id, n, l.name, t(l.createdAt), l.serverId, t(l.updatedAt), l.cover)
            })
            dao.insertItems(file.libraries.flatMap { l ->
                l.items.mapIndexed { n, i ->
                    ItemRow(
                        l.id, n, i.source, i.key, i.url, i.title, i.artist, i.durationMs, i.state, i.thumbnail, i.albumThumbnail,
                        i.why, i.mbid, i.artistMbid, i.artistState, t(i.addedAt), t(i.updatedAt),
                        SyncJson.encodeToString(ListSerializer(SourceRefDto.serializer()), i.sources.map { SourceRefDto(it.source, it.id, it.url) }),
                    )
                }
            })
            dao.insertTombstones(file.libraries.flatMap { l ->
                l.removed.mapIndexed { n, x -> TombstoneRow(l.id, n, x.source, x.key, x.url, x.title, x.artist, t(x.removedAt)) }
            })
            dao.insertDeletedLists(file.deleted.mapIndexed { n, d -> DeletedListRow(n, d.id, d.serverId, d.name, t(d.deletedAt)) })
        }
    }

    override fun loadHistory(): List<HistoryEntry> = dao.history().map {
        HistoryEntry(it.videoId, it.title, it.artist, p(it.playedAt), it.source, it.durationMs, it.playedMs, it.completed, it.device, it.thumbnail, it.eventId)
    }

    override fun saveHistory(entries: List<HistoryEntry>) {
        db.runInTransaction {
            dao.clearHistory()
            entries.chunked(500).forEach { chunk ->
                dao.insertHistory(chunk.map {
                    HistoryRow(0, it.eventId, it.videoId, it.title, it.artist, t(it.playedAt), it.source, it.durationMs, it.playedMs, it.completed, it.device, it.thumbnail)
                })
            }
        }
    }

    override fun loadSettings(): SyncSettings {
        val r = dao.settings() ?: return SyncSettings()
        return SyncSettings(
            r.syncUrl, r.syncEnabled, r.syncEmail, pn(r.syncLastAt), r.syncCursor, r.syncCursorUrl, r.syncCursorEmail,
            r.eventCursor, r.eventCursorUrl, r.eventCursorEmail, pn(r.historyClearedAt), pn(r.historyClearPending),
            r.syncTwoWay, r.dailyMixId, r.dailyMixDate,
        )
    }

    override fun saveSettings(settings: SyncSettings) {
        val s = settings
        dao.putSettings(
            SettingsRow(
                1, s.syncUrl, s.syncEnabled, s.syncEmail, tn(s.syncLastAt), s.syncCursor, s.syncCursorUrl, s.syncCursorEmail,
                s.eventCursor, s.eventCursorUrl, s.eventCursorEmail, tn(s.historyClearedAt), tn(s.historyClearPending),
                s.syncTwoWay, s.dailyMixId, s.dailyMixDate,
            ),
        )
    }

    override fun loadOutboxJson(): String? = dao.document("outbox")
    override fun saveOutboxJson(json: String) = dao.putDocument(DocumentRow("outbox", json))
    override fun loadLinksJson(): String? = dao.document("links")
    override fun saveLinksJson(json: String) = dao.putDocument(DocumentRow("links", json))
}
