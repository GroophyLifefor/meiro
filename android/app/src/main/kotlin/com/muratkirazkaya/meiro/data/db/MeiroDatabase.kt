package com.muratkirazkaya.meiro.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

// Times are stored as RFC 3339 text with nanoseconds, so sub-second precision survives (LWW depends on it).

@Entity(tableName = "libraries")
data class LibraryRow(
    @PrimaryKey val id: String,
    /** Order of the lists, oldest first. */
    val position: Int,
    val name: String,
    val createdAt: String,
    val serverId: String,
    val updatedAt: String,
    val cover: String,
)

@Entity(
    tableName = "library_items",
    primaryKeys = ["libraryId", "position"],
    foreignKeys = [ForeignKey(LibraryRow::class, ["id"], ["libraryId"], onDelete = ForeignKey.CASCADE)],
)
data class ItemRow(
    val libraryId: String,
    val position: Int,
    val source: String,
    val key: String,
    val url: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val state: String,
    val thumbnail: String,
    val albumThumbnail: String,
    val why: String,
    val mbid: String,
    val artistMbid: String,
    val artistState: String,
    val addedAt: String,
    val updatedAt: String,
    /** JSON list of provider copies (SourceRef). */
    val sourcesJson: String,
)

@Entity(
    tableName = "tombstones",
    primaryKeys = ["libraryId", "position"],
    foreignKeys = [ForeignKey(LibraryRow::class, ["id"], ["libraryId"], onDelete = ForeignKey.CASCADE)],
)
data class TombstoneRow(
    val libraryId: String,
    val position: Int,
    val source: String,
    val key: String,
    val url: String,
    val title: String,
    val artist: String,
    val removedAt: String,
)

@Entity(tableName = "deleted_lists")
data class DeletedListRow(
    @PrimaryKey val position: Int,
    val id: String,
    val serverId: String,
    val name: String,
    val deletedAt: String,
)

@Entity(tableName = "history", indices = [Index("eventId"), Index("playedAt")])
data class HistoryRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val eventId: String,
    val videoId: String,
    val title: String,
    val artist: String,
    val playedAt: String,
    val source: String,
    val durationMs: Long,
    val playedMs: Long,
    val completed: Boolean,
    val device: String,
    val thumbnail: String,
)

/** Cursors and the other sync settings: one row. */
@Entity(tableName = "sync_settings")
data class SettingsRow(
    @PrimaryKey val id: Int = 1,
    val syncUrl: String,
    val syncEnabled: Boolean,
    val syncEmail: String,
    val syncLastAt: String?,
    val syncCursor: Long,
    val syncCursorUrl: String,
    val syncCursorEmail: String,
    val eventCursor: Long,
    val eventCursorUrl: String,
    val eventCursorEmail: String,
    val historyClearedAt: String?,
    val historyClearPending: String?,
    val syncTwoWay: Boolean,
    val dailyMixId: String,
    val dailyMixDate: String,
)

/** The outbox and the links, as JSON documents (their own types serialize to the wire format). */
@Entity(tableName = "documents")
data class DocumentRow(@PrimaryKey val name: String, val json: String)

@Dao
interface MeiroDao {
    @Query("SELECT * FROM libraries ORDER BY position") fun libraries(): List<LibraryRow>
    @Query("SELECT * FROM library_items ORDER BY libraryId, position") fun items(): List<ItemRow>
    @Query("SELECT * FROM tombstones ORDER BY libraryId, position") fun tombstones(): List<TombstoneRow>
    @Query("SELECT * FROM deleted_lists ORDER BY position") fun deletedLists(): List<DeletedListRow>
    @Query("DELETE FROM libraries") fun clearLibraries()
    @Query("DELETE FROM library_items") fun clearItems()
    @Query("DELETE FROM tombstones") fun clearTombstones()
    @Query("DELETE FROM deleted_lists") fun clearDeletedLists()
    @Insert fun insertLibraries(rows: List<LibraryRow>)
    @Insert fun insertItems(rows: List<ItemRow>)
    @Insert fun insertTombstones(rows: List<TombstoneRow>)
    @Insert fun insertDeletedLists(rows: List<DeletedListRow>)

    @Query("SELECT * FROM history ORDER BY rowId") fun history(): List<HistoryRow>
    @Query("DELETE FROM history") fun clearHistory()
    @Insert fun insertHistory(rows: List<HistoryRow>)

    @Query("SELECT * FROM sync_settings WHERE id = 1") fun settings(): SettingsRow?
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) fun putSettings(row: SettingsRow)

    @Query("SELECT json FROM documents WHERE name = :name") fun document(name: String): String?
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) fun putDocument(row: DocumentRow)
}

@Database(
    entities = [LibraryRow::class, ItemRow::class, TombstoneRow::class, DeletedListRow::class, HistoryRow::class, SettingsRow::class, DocumentRow::class],
    version = 1,
    exportSchema = true,
)
abstract class MeiroDatabase : RoomDatabase() {
    abstract fun dao(): MeiroDao
}
