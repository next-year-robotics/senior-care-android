package com.nextyearrobotics.seniorcare.places

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import com.kakao.vectormap.label.*
import com.nextyearrobotics.seniorcare.BuildConfig
import kotlinx.coroutines.delay

/** 주행 화면의 작은 지도: 내 위치(파란 점)와 목적지 핀만. 길 안내는 하지 않는다(어르신이 늘 다니는 길). */
interface TripMap {
    @Composable fun Content(me: MapPoint?, destination: MapPoint?, destinationLabel: String, modifier: Modifier, onFailure: () -> Unit)
}

class KakaoTripMap : TripMap {
    @Composable override fun Content(me: MapPoint?, destination: MapPoint?, destinationLabel: String, modifier: Modifier, onFailure: () -> Unit) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val failure by rememberUpdatedState(onFailure)
        var map by remember { mutableStateOf<KakaoMap?>(null) }
        val start = remember { me ?: destination ?: MapPoint(37.2411, 127.0797) } // 경희대 국제캠퍼스(부스 장소표 기준)
        val density = context.resources.displayMetrics.density
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
                    override fun getPosition(): LatLng = LatLng.from(start.lat, start.lon)
                    override fun getZoomLevel() = 16
                    override fun onMapReady(ready: KakaoMap) { if (!disposed) map = ready }
                })
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
            } catch (_: Exception) { failure() }
            onDispose { disposed = true; lifecycle.removeObserver(observer); view.pause(); view.finish() }
        }
        LaunchedEffect(view) { delay(10_000); if (map == null) failure() }
        val styles = remember(density) {
            LabelStyles.from(LabelStyle.from(meDot((40 * density).toInt())).setAnchorPoint(0.5f, 0.5f).setApplyDpScale(false)) to
                LabelStyles.from(LabelStyle.from(pin((44 * density).toInt())).setAnchorPoint(0.5f, 1f).setApplyDpScale(false)
                    .setTextStyles(LabelTextStyle.from((18 * density).toInt(), 0xFF2C365A.toInt(), (3 * density).toInt(), 0xFFFFFFFF.toInt())))
        }
        var meLabel by remember { mutableStateOf<Label?>(null) }
        LaunchedEffect(map, destination, destinationLabel) {
            val layer = map?.labelManager?.layer ?: return@LaunchedEffect
            layer.removeAll()
            meLabel = null
            destination?.let { layer.addLabel(LabelOptions.from(LatLng.from(it.lat, it.lon)).setStyles(styles.second).setRank(1)
                .setTexts(LabelTextBuilder().setTexts(destinationLabel))) }
        }
        LaunchedEffect(map, me, destination, destinationLabel) {
            val ready = map ?: return@LaunchedEffect
            val layer = ready.labelManager?.layer ?: return@LaunchedEffect
            val at = me ?: run {
                destination?.let { ready.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(it.lat, it.lon))) }
                return@LaunchedEffect
            }
            val position = LatLng.from(at.lat, at.lon)
            meLabel?.moveTo(position) ?: run { meLabel = layer.addLabel(LabelOptions.from(position).setStyles(styles.first).setRank(2)) }
            // 목적지가 걸어갈 거리(5 km 안)면 둘 다 보이게, 멀면 내 위치만
            val near = destination?.takeIf { distanceM(at, it) < 5_000 }
            ready.moveCamera(if (near == null) CameraUpdateFactory.newCenterPosition(position)
                else CameraUpdateFactory.fitMapPoints(arrayOf(position, LatLng.from(near.lat, near.lon)), (56 * density).toInt()))
        }
        AndroidView(factory = { view }, modifier = modifier)
    }

    private fun distanceM(a: MapPoint, b: MapPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(a.lat)) * kotlin.math.cos(Math.toRadians(b.lat)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    private fun meDot(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = 0x402F6FDE; c.drawCircle(r, r, r, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(r, r, r * 0.45f, p)
        p.color = 0xFF2F6FDE.toInt(); c.drawCircle(r, r, r * 0.32f, p)
        return b
    }

    private fun pin(size: Int): Bitmap {
        val w = size * 0.75f
        val b = Bitmap.createBitmap(w.toInt(), size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = w / 2f
        val path = Path().apply {
            addCircle(r, r, r, Path.Direction.CW)
            moveTo(r * 0.25f, r * 1.55f); lineTo(r, size.toFloat()); lineTo(r * 1.75f, r * 1.55f); close()
        }
        p.color = 0xFFA43A38.toInt(); c.drawPath(path, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(r, r, r * 0.4f, p)
        return b
    }
}

/** 지도 없이(설정의 화면 미리보기·시험). */
object NoTripMap : TripMap {
    @Composable override fun Content(me: MapPoint?, destination: MapPoint?, destinationLabel: String, modifier: Modifier, onFailure: () -> Unit) {
        LaunchedEffect(Unit) { onFailure() }
    }
}
