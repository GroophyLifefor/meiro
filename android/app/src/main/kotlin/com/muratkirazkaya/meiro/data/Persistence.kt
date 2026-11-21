package com.muratkirazkaya.meiro.data

/**
 * Where the stores keep what they hold. The stores keep their state in memory, like the desktop's, and hand the
 * whole of it here after each change; Room implements it on the phone, [MemoryPersistence] in unit tests.
 * Every method runs on the caller's thread, so callers use a background dispatcher.
 */
interface Persistence {
    fun loadLibraries(): LibraryFile
    fun saveLibraries(file: LibraryFile)
    fun loadHistory(): List<HistoryEntry>
    fun saveHistory(entries: List<HistoryEntry>)
    fun loadSettings(): SyncSettings
    fun saveSettings(settings: SyncSettings)
    /** The outbox and the links are kept as JSON documents (their own types serialize to the wire format). */
    fun loadOutboxJson(): String?
    fun saveOutboxJson(json: String)
    fun loadLinksJson(): String?
    fun saveLinksJson(json: String)
}

data class LibraryFile(val libraries: List<Library> = emptyList(), val deleted: List<DeletedList> = emptyList())

class MemoryPersistence : Persistence {
    @Volatile var libraries = LibraryFile()
    @Volatile var history: List<HistoryEntry> = emptyList()
    @Volatile var settings = SyncSettings()
    @Volatile var outbox: String? = null
    @Volatile var links: String? = null
    override fun loadLibraries() = libraries
    override fun saveLibraries(file: LibraryFile) { libraries = file }
    override fun loadHistory() = history
    override fun saveHistory(entries: List<HistoryEntry>) { history = entries }
    override fun loadSettings() = settings
    override fun saveSettings(settings: SyncSettings) { this.settings = settings }
    override fun loadOutboxJson() = outbox
    override fun saveOutboxJson(json: String) { outbox = json }
    override fun loadLinksJson() = links
    override fun saveLinksJson(json: String) { links = json }
}
