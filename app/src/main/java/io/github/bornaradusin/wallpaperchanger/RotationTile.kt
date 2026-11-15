package io.github.bornaradusin.wallpaperchanger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile: pause or resume the rotation. */
class RotationTile : TileService() {
    override fun onStartListening() = show()

    override fun onClick() {
        app.library.togglePaused()
        WidgetUpdates.refresh(this)
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val paused = app.library.data.value.settings.paused
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = "Wallpaper rotation"
        tile.subtitle = if (paused) "Paused" else "On"
        tile.updateTile()
    }
}
