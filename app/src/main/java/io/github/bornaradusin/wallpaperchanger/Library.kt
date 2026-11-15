package io.github.bornaradusin.wallpaperchanger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Everything the app keeps (albums, pictures, rules, settings) and the rotation. Saved to one file
 * in the app's own storage.
 */
class Library(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _data = MutableStateFlow(load())
    val data: StateFlow<Data> = _data.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private fun load(): Data {
        val read = runCatching { json.decodeFromString(Data.serializer(), file.readText()) }.getOrNull() ?: Data()
        // The rotation needs an album to put things in
        return if (read.albums.isEmpty()) read.copy(albums = listOf(Album(newId(), "My wallpapers"))) else read
    }

    private fun update(change: (Data) -> Data): Data = synchronized(lock) {
        val next = change(_data.value)
        if (next != _data.value) {
            _data.value = next
            val text = json.encodeToString(Data.serializer(), next)
            scope.launch {
                synchronized(file) {
                    val temp = File(file.parentFile, "library.json.tmp")
                    temp.writeText(text)
                    temp.renameTo(file)
                }
            }
        }
        next
    }

    private fun changed() = Schedule.update(context, _data.value)

    // ---- the rotation ----

    /**
     * Puts up the next one: on the home screen, and on the lock screen too when it has its own
     * rotation. [home]/[lock] pick which.
     */
    fun next(home: Boolean = true, lock: Boolean = true) {
        update { d ->
            var n = d
            if (home) {
                val (item, bag) = choose(n, Screen.HOME)
                n = n.copy(current = item?.id ?: n.current, bag = bag)
            }
            if (lock && n.settings.lockOwn) {
                val (item, bag) = choose(n, Screen.LOCK)
                n = n.copy(lockCurrent = item?.id ?: n.lockCurrent, lockBag = bag)
            }
            n
        }
    }

    /** The screen went off: the next one goes up now, while no one can see it. */
    fun screenOff() {
        val s = _data.value.settings
        if (s.onUnlock && !s.paused) next()
    }

    fun show(item: Item, screen: Screen = Screen.HOME) {
        update { if (screen == Screen.LOCK) it.copy(lockCurrent = item.id) else it.copy(current = item.id) }
    }

    private fun choose(d: Data, screen: Screen): Pair<Item?, List<String>> {
        val pool = d.pool(screen)
        val current = if (screen == Screen.LOCK) d.lockCurrent else d.current
        val oldBag = if (screen == Screen.LOCK) d.lockBag else d.bag
        if (pool.isEmpty()) return null to emptyList()
        if (pool.size == 1) return pool.first() to emptyList()
        if (d.settings.random) {
            // A shuffled pile: nothing comes back until everything has been shown
            val ids = pool.map { it.id }.toSet()
            var bag = oldBag.filter { it in ids }
            if (bag.isEmpty()) bag = pool.map { it.id }.filter { it != current }.shuffled()
            return pool.first { it.id == bag.first() } to bag.drop(1)
        }
        val at = pool.indexOfFirst { it.id == current }
        return pool[(at + 1) % pool.size] to emptyList()
    }

    // ---- albums ----

    fun addAlbum(name: String): Album {
        val album = Album(newId(), name.trim().ifBlank { "Album" })
        update { it.copy(albums = it.albums + album) }
        return album
    }

    fun renameAlbum(album: Album, name: String) =
        update { d -> d.copy(albums = d.albums.map { if (it.id == album.id) it.copy(name = name.trim().ifBlank { it.name }) else it }) }

    fun setActive(album: Album, active: Boolean, screen: Screen = Screen.HOME) {
        update { d ->
            d.copy(
                albums = d.albums.map { if (it.id != album.id) it else if (screen == Screen.LOCK) it.copy(lock = active) else it.copy(active = active) },
                bag = if (screen == Screen.HOME) emptyList() else d.bag,
                lockBag = if (screen == Screen.LOCK) emptyList() else d.lockBag,
            )
        }
        afterPoolChange()
    }

    fun deleteAlbum(album: Album) {
        val gone = _data.value.folders.filter { it.album == album.id }
        update { d ->
            val left = d.albums.filterNot { it.id == album.id }
            d.copy(
                albums = left.ifEmpty { listOf(Album(newId(), "My wallpapers")) },
                items = d.items.filterNot { it.album == album.id },
                folders = d.folders.filterNot { it.album == album.id },
                bag = emptyList(),
                lockBag = emptyList(),
            )
        }
        gone.forEach { release(it.uri) }
        deleteSharedCopies()
        afterPoolChange()
        changed()
    }

    /** Only this album is used from now on (a rule, or "next album"), on both screens. */
    fun switchTo(albumId: String) {
        if (_data.value.albums.none { it.id == albumId }) return
        update { d ->
            d.copy(
                albums = d.albums.map { it.copy(active = it.id == albumId, lock = if (d.settings.lockOwn) it.id == albumId else it.lock) },
                bag = emptyList(),
                lockBag = emptyList(),
            )
        }
        next()
    }

    /** Moves on to the album after the one in use. */
    fun nextAlbum() {
        val albums = _data.value.albums
        if (albums.isEmpty()) return
        val at = albums.indexOfFirst { it.active }
        switchTo(albums[(at + 1) % albums.size].id)
    }

    /** The current one is gone or no longer in the rotation: move on. */
    private fun afterPoolChange() {
        val d = _data.value
        val home = d.current == null || d.pool(Screen.HOME).none { it.id == d.current }
        val lock = d.settings.lockOwn && (d.lockCurrent == null || d.pool(Screen.LOCK).none { it.id == d.lockCurrent })
        if (home || lock) next(home, lock)
    }

    // ---- pictures and videos ----

    fun addFiles(album: String, uris: List<Uri>) {
        val new = uris.mapNotNull { uri ->
            keep(uri)
            val type = context.contentResolver.getType(uri).orEmpty()
            if (!type.startsWith("image/") && !type.startsWith("video/")) return@mapNotNull null
            Item(newId(), album, uri.toString(), type.startsWith("video/"), displayName(uri))
        }
        add(new)
    }

    /** Pictures shared to the app are copied in, as the app that shared them can take them back. */
    fun addShared(album: String, uris: List<Uri>, fallbackType: String?) {
        val dir = File(context.filesDir, "shared").apply { mkdirs() }
        val new = uris.mapNotNull { uri ->
            runCatching {
                val type = context.contentResolver.getType(uri) ?: fallbackType.orEmpty()
                val video = type.startsWith("video/")
                val name = displayName(uri)
                val copy = File(dir, newId() + "." + (name.substringAfterLast('.', if (video) "mp4" else "jpg")))
                context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                Item(newId(), album, Uri.fromFile(copy).toString(), video, name)
            }.getOrNull()
        }
        add(new)
    }

    private fun add(new: List<Item>) {
        if (new.isEmpty()) return
        update { d -> d.copy(items = d.items + new.filter { n -> d.items.none { it.uri == n.uri && it.album == n.album } }) }
        afterPoolChange()
    }

    fun remove(items: Collection<Item>) {
        val ids = items.map { it.id }.toSet()
        update { d -> d.copy(items = d.items.filterNot { it.id in ids }) }
        items.filter { it.folder == null }.forEach { if (_data.value.items.none { o -> o.uri == it.uri }) release(it.uri) }
        deleteSharedCopies()
        afterPoolChange()
    }

    fun setCrop(item: Item, crop: Crop?) =
        update { d -> d.copy(items = d.items.map { if (it.id == item.id) it.copy(crop = crop) else it }) }

    // ---- folders ----

    fun addFolder(album: String, uri: Uri) {
        keep(uri)
        val name = DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
        update { d ->
            if (d.folders.any { it.uri == uri.toString() && it.album == album }) d
            else d.copy(folders = d.folders + Folder(uri.toString(), album, name))
        }
        rescan()
    }

    fun removeFolder(folder: Folder) {
        update { d ->
            d.copy(
                folders = d.folders.filterNot { it.uri == folder.uri && it.album == folder.album },
                items = d.items.filterNot { it.folder == folder.uri && it.album == folder.album },
            )
        }
        if (_data.value.folders.none { it.uri == folder.uri }) release(folder.uri)
        afterPoolChange()
    }

    /** Looks through the folders again if it's been long enough. */
    fun rescanIfDue() {
        val d = _data.value
        val hours = d.settings.rescanHours
        if (hours > 0 && d.folders.isNotEmpty() && System.currentTimeMillis() - d.scanned > hours * 3_600_000L) rescan()
    }

    fun rescan() {
        if (_scanning.value) return
        _scanning.value = true
        scope.launch {
            try {
                val d = _data.value
                val found = d.folders.associateWith { folder ->
                    runCatching {
                        val tree = Uri.parse(folder.uri)
                        ArrayList<Pair<String, Pair<String, Boolean>>>().also {
                            walk(tree, DocumentsContract.getTreeDocumentId(tree), d.settings.subfolders, it)
                        }
                    }.getOrNull()
                }
                update { now ->
                    var items = now.items
                    found.forEach { (folder, files) ->
                        if (files == null) return@forEach // couldn't be read now: keep what was there
                        val old = items.filter { it.folder == folder.uri && it.album == folder.album }.associateBy { it.uri }
                        val fresh = files.map { (uri, rest) -> old[uri] ?: Item(newId(), folder.album, uri, rest.second, rest.first, folder.uri) }
                        items = items.filterNot { it.folder == folder.uri && it.album == folder.album } + fresh
                    }
                    now.copy(items = items, scanned = System.currentTimeMillis())
                }
                afterPoolChange()
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun walk(tree: Uri, documentId: String, deeper: Boolean, into: MutableList<Pair<String, Pair<String, Boolean>>>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val here = ArrayList<Pair<String, Pair<String, Boolean>>>()
        val folders = ArrayList<String>()
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1).orEmpty()
                val type = c.getString(2).orEmpty()
                when {
                    type == DocumentsContract.Document.MIME_TYPE_DIR -> if (deeper && !name.startsWith(".")) folders.add(id)
                    type.startsWith("image/") || type.startsWith("video/") ->
                        here.add(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString() to (name to type.startsWith("video/")))
                }
            }
        }
        into.addAll(here.sortedBy { it.second.first.lowercase() })
        folders.forEach { walk(tree, it, deeper, into) }
    }

    // ---- rules ----

    fun saveRule(rule: Rule) {
        update { d -> d.copy(rules = if (d.rules.any { it.id == rule.id }) d.rules.map { if (it.id == rule.id) rule else it } else d.rules + rule) }
        changed()
    }

    fun deleteRule(rule: Rule) {
        update { d -> d.copy(rules = d.rules.filterNot { it.id == rule.id }) }
        changed()
    }

    fun run(rule: Rule) {
        if (!rule.enabled || _data.value.settings.paused) return
        when (val a = rule.action) {
            Action.Next -> next()
            is Action.SwitchAlbum -> switchTo(a.album)
        }
    }

    // ---- settings ----

    fun setSettings(change: (Settings) -> Settings) {
        val before = _data.value.settings
        val after = update { it.copy(settings = change(it.settings)) }.settings
        if (before.subfolders != after.subfolders) rescan()
        if (before.random != after.random) update { it.copy(bag = emptyList(), lockBag = emptyList()) }
        if (!before.lockOwn && after.lockOwn) afterPoolChange()
        if (before.interval != after.interval || before.paused != after.paused) changed()
    }

    fun togglePaused() = setSettings { it.copy(paused = !it.paused) }

    // ---- helpers ----

    private fun keep(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun release(uri: String) {
        val u = Uri.parse(uri)
        if (u.scheme != "content") return
        runCatching { context.contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Copies of shared pictures that nothing uses any more. */
    private fun deleteSharedCopies() {
        val used = _data.value.items.map { it.uri }.toSet()
        File(context.filesDir, "shared").listFiles()?.forEach { if (Uri.fromFile(it).toString() !in used) it.delete() }
    }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    companion object {
        fun newId() = UUID.randomUUID().toString()
    }
}
