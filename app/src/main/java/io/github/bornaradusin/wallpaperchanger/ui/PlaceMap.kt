package io.github.bornaradusin.wallpaperchanger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/** An OpenStreetMap map: tap to pick the place; the circle shows the radius. */
@Composable
fun PlaceMap(lat: Double?, lon: Double?, radius: Float, onPick: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val start = remember { if (lat != null && lon != null) lat to lon else lastLocation(context) ?: (53.3498 to -6.2603) }
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osm")
            osmdroidTileCache = File(context.cacheDir, "osm/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(start.first, start.second))
        }
    }
    val pick = remember { arrayOf(onPick) }
    pick[0] = onPick
    DisposableEffect(Unit) {
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                pick[0](p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint) = false
        }))
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView({ map }, Modifier.fillMaxSize()) { m ->
        m.overlays.removeAll { it is Marker || it is Polygon }
        if (lat != null && lon != null) {
            val point = GeoPoint(lat, lon)
            m.overlays.add(Polygon(m).apply {
                setPoints(Polygon.pointsAsCircle(point, radius.toDouble()))
                fillPaint.color = 0x332196F3
                outlinePaint.color = 0xFF2196F3.toInt()
                outlinePaint.strokeWidth = 3f
            })
            m.overlays.add(Marker(m).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setInfoWindow(null)
            })
        }
        m.invalidate()
    }
}
