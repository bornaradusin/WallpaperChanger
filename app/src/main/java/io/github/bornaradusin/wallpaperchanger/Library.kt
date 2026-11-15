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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A picture or a video to show. */
@Serializable
data class Media(val uri: String, val video: Boolean, val name: String)

/** Where the pictures and videos come from: a whole folder, or one file. */
@Serializable
data class Source(val uri: String, val folder: Boolean, val name: String)

enum class VideoEnd { LOOP, ONCE }

data class Settings(
    val shuffle: Boolean = true,
    val subfolders: Boolean = true,
    val videoEnd: VideoEnd = VideoEnd.LOOP,
)

data class LibraryState(
    val sources: List<Source> = emptyList(),
    val settings: Settings = Settings(),
    /** Everything found in the sources, the last time they were looked through. */
    val media: List<Media> = emptyList(),
    val scanning: Boolean = false,
    val current: Media? = null,
)

/**
 * The pictures and videos, and which one is showing. Moving on to the next happens each time the
 * phone locks (see [WallService]), or from the app.
 */
class Library(private val context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow(
        LibraryState(
            sources = read(KEY_SOURCES, ListSerializer(Source.serializer())).orEmpty(),
            settings = Settings(
                shuffle = prefs.getBoolean(KEY_SHUFFLE, true),
                subfolders = prefs.getBoolean(KEY_SUBFOLDERS, true),
                videoEnd = runCatching { VideoEnd.valueOf(prefs.getString(KEY_VIDEO_END, "LOOP")!!) }.getOrDefault(VideoEnd.LOOP),
            ),
            media = read(KEY_MEDIA, ListSerializer(Media.serializer())).orEmpty(),
            current = prefs.getString(KEY_CURRENT, null)?.let { runCatching { json.decodeFromString(Media.serializer(), it) }.getOrNull() },
        ),
    )
    val state: StateFlow<LibraryState> = _state

    private fun <T> read(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    // ---- sources ----

    /** A folder picked in Android's file picker; it stays readable after a restart. */
    fun addFolder(uri: Uri) {
        keep(uri)
        val name = DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
        addSources(listOf(Source(uri.toString(), folder = true, name = name)))
    }

    /** Pictures and videos picked one by one. */
    fun addFiles(uris: List<Uri>) {
        uris.forEach { keep(it) }
        addSources(uris.map { Source(it.toString(), folder = false, name = displayName(it)) })
    }

    fun remove(source: Source) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(source.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        saveSources(_state.value.sources.filterNot { it.uri == source.uri })
        rescan()
    }

    private fun addSources(new: List<Source>) {
        val have = _state.value.sources
        saveSources(have + new.filter { n -> have.none { it.uri == n.uri } })
        rescan(thenShowIfNothing = true)
    }

    private fun keep(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun saveSources(sources: List<Source>) {
        prefs.edit().putString(KEY_SOURCES, json.encodeToString(ListSerializer(Source.serializer()), sources)).apply()
        _state.update { it.copy(sources = sources) }
    }

    // ---- settings ----

    fun setSettings(settings: Settings) {
        val subfoldersChanged = settings.subfolders != _state.value.settings.subfolders
        prefs.edit()
            .putBoolean(KEY_SHUFFLE, settings.shuffle)
            .putBoolean(KEY_SUBFOLDERS, settings.subfolders)
            .putString(KEY_VIDEO_END, settings.videoEnd.name)
            .apply()
        _state.update { it.copy(settings = settings) }
        if (subfoldersChanged) rescan()
    }

    // ---- moving on ----

    /** Shows the next picture or video. Called each time the phone locks. */
    fun next() {
        scope.launch {
            lock.withLock {
                // Folders change: look through them again now and then, while the screen is off anyway
                val media = if (_state.value.media.isEmpty() || System.currentTimeMillis() - prefs.getLong(KEY_SCANNED, 0L) > RESCAN_MS) {
                    scan()
                } else {
                    _state.value.media
                }
                pick(media)
            }
        }
    }

    fun rescan(thenShowIfNothing: Boolean = false) {
        scope.launch {
            lock.withLock {
                val media = scan()
                val current = _state.value.current
                if (current == null || media.none { it.uri == current.uri }) {
                    if (thenShowIfNothing || current != null) pick(media)
                }
            }
        }
    }

    private fun pick(media: List<Media>) {
        val current = _state.value.current
        val next = when {
            media.isEmpty() -> null
            media.size == 1 -> media.first()
            _state.value.settings.shuffle -> {
                // A shuffled pile: nothing comes back until everything has been shown
                val uris = media.map { it.uri }.toSet()
                var bag = (read(KEY_BAG, ListSerializer(String.serializer())) ?: emptyList()).filter { it in uris }
                if (bag.isEmpty()) bag = media.map { it.uri }.filter { it != current?.uri }.shuffled()
                val chosen = bag.first()
                prefs.edit().putString(KEY_BAG, json.encodeToString(ListSerializer(String.serializer()), bag.drop(1))).apply()
                media.first { it.uri == chosen }
            }
            else -> {
                val at = media.indexOfFirst { it.uri == current?.uri }
                media[(at + 1) % media.size]
            }
        }
        prefs.edit().putString(KEY_CURRENT, next?.let { json.encodeToString(Media.serializer(), it) }).apply()
        _state.update { it.copy(current = next) }
    }

    /** Everything in the sources: folders in name order, then the single files. */
    private fun scan(): List<Media> {
        _state.update { it.copy(scanning = true) }
        val found = ArrayList<Media>()
        _state.value.sources.forEach { source ->
            runCatching {
                if (source.folder) {
                    val tree = Uri.parse(source.uri)
                    walk(tree, DocumentsContract.getTreeDocumentId(tree), _state.value.settings.subfolders, found)
                } else {
                    val uri = Uri.parse(source.uri)
                    val type = context.contentResolver.getType(uri).orEmpty()
                    if (type.startsWith("image/") || type.startsWith("video/")) {
                        found.add(Media(source.uri, type.startsWith("video/"), source.name))
                    }
                }
            }
        }
        prefs.edit()
            .putString(KEY_MEDIA, json.encodeToString(ListSerializer(Media.serializer()), found))
            .putLong(KEY_SCANNED, System.currentTimeMillis())
            .apply()
        _state.update { it.copy(media = found, scanning = false) }
        return found
    }

    private fun walk(tree: Uri, documentId: String, deeper: Boolean, into: MutableList<Media>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val here = ArrayList<Media>()
        val folders = ArrayList<String>()
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1).orEmpty()
                val type = c.getString(2).orEmpty()
                when {
                    type == DocumentsContract.Document.MIME_TYPE_DIR -> if (deeper && !name.startsWith(".")) folders.add(id)
                    type.startsWith("image/") || type.startsWith("video/") ->
                        here.add(Media(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), type.startsWith("video/"), name))
                }
            }
        }
        into.addAll(here.sortedBy { it.name.lowercase() })
        folders.forEach { walk(tree, it, deeper, into) }
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_MEDIA = "media"
        const val KEY_SCANNED = "scanned"
        const val KEY_CURRENT = "current"
        const val KEY_BAG = "bag"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_SUBFOLDERS = "subfolders"
        const val KEY_VIDEO_END = "videoEnd"
        const val RESCAN_MS = 30 * 60_000L
    }
}
