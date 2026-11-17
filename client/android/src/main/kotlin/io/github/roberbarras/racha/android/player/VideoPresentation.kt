package io.github.roberbarras.racha.android.player

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The video on a screen plugged into the phone or tablet (HDMI, USB-C, Miracast; H18): full screen, its own aspect
 * ratio, subtitles drawn here too. The phone keeps the player as the remote.
 */
class VideoPresentation(context: Context, display: Display, private val engine: Media3Engine) : Presentation(context, display) {
    val surface = SurfaceView(context)
    private val subtitle = TextView(context)
    private val scope = MainScope()
    private var aspect = 16f / 9f

    private val frame = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val (w, h) = if (width / aspect <= height) width to (width / aspect).toInt() else (height * aspect).toInt() to height
            surface.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            subtitle.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val width = right - left
            val height = bottom - top
            val x = (width - surface.measuredWidth) / 2
            val y = (height - surface.measuredHeight) / 2
            surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
            val sx = (width - subtitle.measuredWidth) / 2
            val sy = height - subtitle.measuredHeight - height / 12
            subtitle.layout(sx, sy, sx + subtitle.measuredWidth, sy + subtitle.measuredHeight)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        frame.setBackgroundColor(Color.BLACK)
        subtitle.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(24, 8, 24, 8)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        frame.addView(surface)
        frame.addView(subtitle)
        setContentView(frame)
        scope.launch {
            engine.snapshot.collect { s ->
                if (s.videoWidth > 0 && s.videoHeight > 0 && s.videoWidth.toFloat() / s.videoHeight != aspect) {
                    aspect = s.videoWidth.toFloat() / s.videoHeight
                    frame.requestLayout()
                }
                subtitle.text = s.subtitleText
                subtitle.visibility = if (s.subtitleText.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onStop() {
        scope.cancel()
        super.onStop()
    }
}
