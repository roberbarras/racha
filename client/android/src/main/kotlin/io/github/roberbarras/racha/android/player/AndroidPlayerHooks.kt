package io.github.roberbarras.racha.android.player

import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.graphics.createBitmap
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.ui.player.PlayerHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android side of the player. App-scoped (the player's ViewModel outlives activity recreation), bound to the visible
 * activity by [attach]/[detach]. Nothing here runs while the app is in the background except PiP, which is the
 * activity itself in a small window.
 */
class AndroidPlayerHooks(private val context: Context) : PlayerHooks {
    private var activity: ComponentActivity? = null
    private val pip = MutableStateFlow(false)
    private val surfaces = HashMap<PlayerEngine, SurfaceView>()
    private var playing = false
    private var aspect = Rational(16, 9)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { pip.value = it.isInPictureInPictureMode }
    private val leaveHint = Runnable { if (playing && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip() }

    override val inPictureInPicture: StateFlow<Boolean> = pip.asStateFlow()

    /** Engines currently shown on screen (instrumented tests read their state). */
    @VisibleForTesting
    val visibleEngines: Set<PlayerEngine> get() = surfaces.keys.toSet()

    fun attach(activity: ComponentActivity) {
        this.activity = activity
        activity.addOnPictureInPictureModeChangedListener(pipListener)
        activity.addOnUserLeaveHintListener(leaveHint)
        pip.value = activity.isInPictureInPictureMode
    }

    fun detach(activity: ComponentActivity) {
        activity.removeOnPictureInPictureModeChangedListener(pipListener)
        activity.removeOnUserLeaveHintListener(leaveHint)
        if (this.activity === activity) this.activity = null
    }

    override fun createEngine(): PlayerEngine = Media3Engine(context)

    @Composable
    override fun VideoSurface(engine: PlayerEngine, modifier: Modifier) {
        val media = engine as Media3Engine
        val activityContext = LocalContext.current
        val view = remember(engine) { SurfaceView(activityContext) }
        DisposableEffect(engine, view) {
            media.attach(view)
            surfaces[engine] = view
            onDispose {
                media.detach(view)
                surfaces.remove(engine)
            }
        }
        AndroidView(factory = { view }, modifier = modifier)
    }

    override fun setFullscreen(fullscreen: Boolean) {
        val activity = activity ?: return
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // Landscape video fills a phone only sideways; the user can still rotate within landscape.
            if (aspect.toFloat() >= 1f) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun enterPictureInPicture(videoWidth: Int, videoHeight: Int): Boolean {
        aspect = aspectOf(videoWidth, videoHeight)
        return enterPip()
    }

    private fun enterPip(): Boolean {
        val activity = activity ?: return false
        return runCatching { activity.enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    override fun onPlayingChanged(playing: Boolean, videoWidth: Int, videoHeight: Int) {
        this.playing = playing
        if (videoWidth > 0 && videoHeight > 0) aspect = aspectOf(videoWidth, videoHeight)
        val activity = activity ?: return
        // The screen stays on only while a video actually plays; paused, the normal timeout applies (battery).
        if (playing) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        runCatching { activity.setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(aspect)
        // The system animates from where the video is on screen instead of from the whole window.
        .setSourceRectHint(surfaces.values.firstOrNull()?.let { view -> Rect().takeIf { view.getGlobalVisibleRect(it) } })
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true) }
        .build()

    override fun brightness(): Float {
        val window = activity?.window ?: return 0.5f
        val own = window.attributes.screenBrightness
        if (own >= 0f) return own
        val system = runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return system / 255f
    }

    /** Window brightness only: the system setting is untouched and comes back when the player closes. */
    override fun setBrightness(value: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
    }

    override fun onClosed() {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    override fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun setVolume(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value.coerceIn(0f, 1f) * max), 0)
    }

    override suspend fun screenshot(engine: PlayerEngine, name: String): String? {
        val view = surfaces[engine] ?: return null
        val snapshot = engine.snapshot.value
        val width = snapshot.videoWidth.takeIf { it > 0 } ?: view.width
        val height = snapshot.videoHeight.takeIf { it > 0 } ?: view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = createBitmap(width, height)
        val copied = suspendCancellableCoroutine { cont ->
            PixelCopy.request(view, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }
        if (!copied) return null
        return withContext(Dispatchers.IO) { save(bitmap, "${name.safeFileName()}.png").also { bitmap.recycle() } }
    }

    private fun save(bitmap: Bitmap, fileName: String): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Racha")
        }
        val resolver = context.contentResolver
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "${Environment.DIRECTORY_PICTURES}/Racha/$fileName"
        }
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Racha").apply { mkdirs() }
        File(dir, fileName).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.path
    }

    override fun language(): String = Locale.getDefault().language.ifEmpty { "en" }

    private companion object {
        /** PiP accepts aspect ratios between 1:2.39 and 2.39:1. */
        fun aspectOf(width: Int, height: Int): Rational {
            if (width <= 0 || height <= 0) return Rational(16, 9)
            val ratio = width.toFloat() / height
            return when {
                ratio > 2.39f -> Rational(239, 100)
                ratio < 1 / 2.39f -> Rational(100, 239)
                else -> Rational(width, height)
            }
        }

        fun String.safeFileName() = replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(120)
    }
}
