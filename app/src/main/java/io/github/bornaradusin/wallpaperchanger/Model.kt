package io.github.bornaradusin.wallpaperchanger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A set of pictures and videos. Only active albums are used for the rotation. */
@Serializable
data class Album(val id: String, val name: String, val active: Boolean = true)

/** One picture or video in an album. */
@Serializable
data class Item(
    val id: String,
    val album: String,
    val uri: String,
    val video: Boolean,
    val name: String,
    /** The folder it was found in, or null when it was added on its own. */
    val folder: String? = null,
    /** The part of the picture to show, if it was cropped. */
    val crop: Crop? = null,
)

/** A part of a picture, as fractions of its width and height. */
@Serializable
data class Crop(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width get() = r - l
    val height get() = b - t
}

/** A folder whose pictures and videos go into an album, and are looked for again now and then. */
@Serializable
data class Folder(val uri: String, val album: String, val name: String)

@Serializable
sealed interface Trigger {
    /** At a time of day, on some days. [days]: bit 0 is Monday … bit 6 is Sunday. */
    @Serializable
    @SerialName("time")
    data class Time(val hour: Int, val minute: Int, val days: Int = ALL_DAYS) : Trigger

    /** On arriving at a place. */
    @Serializable
    @SerialName("place")
    data class Place(val lat: Double, val lon: Double, val radius: Float = 200f, val label: String = "") : Trigger

    /** On connecting to a Wi-Fi network. */
    @Serializable
    @SerialName("wifi")
    data class Wifi(val ssid: String) : Trigger

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKENDS = 0b1100000
    }
}

@Serializable
sealed interface Action {
    @Serializable
    @SerialName("next")
    data object Next : Action

    @Serializable
    @SerialName("album")
    data class SwitchAlbum(val album: String) : Action
}

@Serializable
data class Rule(val id: String, val trigger: Trigger, val action: Action, val enabled: Boolean = true)

enum class VideoEnd { LOOP, ONCE }

/** What happens to a picture or video that isn't the shape of the screen. */
enum class Scaling { FILL, FIT, STRETCH, NONE }

@Serializable
data class Settings(
    /** Minutes between changes; 0 is off. */
    val interval: Int = 0,
    val onUnlock: Boolean = true,
    val doubleTap: Boolean = false,
    val random: Boolean = true,
    val subfolders: Boolean = true,
    /** Hours between looking through the folders again; 0 is off. */
    val rescanHours: Int = 24,
    val scaling: Scaling = Scaling.FILL,
    val scroll: Boolean = true,
    val fullResolution: Boolean = false,
    /** 0–80 percent. */
    val darken: Int = 0,
    val videoEnd: VideoEnd = VideoEnd.LOOP,
    val paused: Boolean = false,
    /** A fixed picture for the lock screen, or null to show the rotation there too. */
    val lockImage: String? = null,
)

@Serializable
data class Data(
    val albums: List<Album> = emptyList(),
    val items: List<Item> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val settings: Settings = Settings(),
    val current: String? = null,
    /** Still to show in this round of the random order. */
    val bag: List<String> = emptyList(),
    val scanned: Long = 0,
) {
    val currentItem get() = items.firstOrNull { it.id == current }

    /** What the rotation picks from: the active albums (all of them if none is active). */
    val pool: List<Item>
        get() {
            val active = albums.filter { it.active }.map { it.id }.toSet()
            return if (active.isEmpty()) items else items.filter { it.album in active }
        }
}
