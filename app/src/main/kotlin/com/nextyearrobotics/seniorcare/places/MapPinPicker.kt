package com.nextyearrobotics.seniorcare.places

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.shape.*
import com.nextyearrobotics.seniorcare.BuildConfig
import kotlinx.coroutines.delay
import kotlin.math.*

interface MapPinPicker {
    @Composable fun Content(target: MapPoint?, radius: Float, modifier: Modifier, onMoved: (MapPoint) -> Unit, onFailure: () -> Unit)
}
class KakaoMapPinPicker : MapPinPicker {
    @Composable override fun Content(target: MapPoint?, radius: Float, modifier: Modifier, onMoved: (MapPoint) -> Unit, onFailure: () -> Unit) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val moved by rememberUpdatedState(onMoved)
        val failure by rememberUpdatedState(onFailure)
        var map by remember { mutableStateOf<KakaoMap?>(null) }
        var center by remember { mutableStateOf(target ?: MapPoint(37.5665, 126.9780)) } // Seoul city hall, never device location.
        val view = remember { MapView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } }
        DisposableEffect(view, lifecycle) {
            var disposed = false
            val observer = LifecycleEventObserver { _, event -> when (event) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            } }
            lifecycle.addObserver(observer)
            try {
                if (BuildConfig.KAKAO_NATIVE_APP_KEY == "dummy") failure()
                else view.start(object : MapLifeCycleCallback() {
                    override fun onMapDestroy() = Unit
                    override fun onMapError(error: Exception) { if (!disposed) failure() }
                }, object : KakaoMapReadyCallback() {
                    override fun getPosition(): LatLng = LatLng.from(center.lat, center.lon)
                    override fun getZoomLevel() = 16
                    override fun onMapReady(ready: KakaoMap) {
                        if (disposed) return
                        map = ready
                        ready.setOnCameraMoveEndListener { _, position, _ ->
                            center = MapPoint(position.position.latitude, position.position.longitude)
                            moved(center)
                        }
                        moved(center)
                    }
                })
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
            } catch (_: Exception) { failure() }
            onDispose { disposed = true; lifecycle.removeObserver(observer); view.pause(); view.finish() }
        }
        LaunchedEffect(view) { delay(10_000); if (map == null) failure() }
        LaunchedEffect(map, target) { target?.let { map?.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(it.lat, it.lon))) } }
        LaunchedEffect(map, center, radius) {
            val layer = map?.shapeManager?.layer ?: return@LaunchedEffect
            val lat = Math.toRadians(center.lat); val lon = Math.toRadians(center.lon); val distance = radius / 6_371_000.0
            val points = (0..64).map { index ->
                val angle = index * 2 * PI / 64
                val nextLat = asin(sin(lat) * cos(distance) + cos(lat) * sin(distance) * cos(angle))
                val nextLon = lon + atan2(sin(angle) * sin(distance) * cos(lat), cos(distance) - sin(lat) * sin(nextLat))
                LatLng.from(Math.toDegrees(nextLat), Math.toDegrees(nextLon))
            }
            layer.removeAll()
            layer.addPolygon(PolygonOptions.from(MapPoints.fromLatLng(points), PolygonStyles.from(0x332C365A, 2f, 0xFF2C365A.toInt())))
        }
        AndroidView(factory = { view }, modifier = modifier)
    }
}
