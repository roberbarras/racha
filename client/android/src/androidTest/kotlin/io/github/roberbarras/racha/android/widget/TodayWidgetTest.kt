package io.github.roberbarras.racha.android.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.android.R
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

/** The widget's layout inflates and draws on the device (RemoteViews only allows a few view types). */
@RunWith(AndroidJUnit4::class)
class TodayWidgetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun layoutRendersWithItsTexts() {
        val views = RemoteViews(context.packageName, R.layout.widget_today).apply {
            setTextViewText(R.id.widget_streak, "Racha de 7 días")
            setTextViewText(R.id.widget_label, "HOY TOCA · AWS Certified Solutions Architect")
            setTextViewText(R.id.widget_title, "IAM Introduction: Users, Groups and Roles")
            setProgressBar(R.id.widget_progress, 100, 42, false)
            setTextViewText(R.id.widget_action, "Continuar")
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(context)
            val view = views.apply(context, parent)
            val width = (320 * context.resources.displayMetrics.density).toInt()
            val height = (140 * context.resources.displayMetrics.density).toInt() // the provider's minimum height
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            assertEquals("Continuar", view.findViewById<TextView>(R.id.widget_action).text.toString())
            val action = view.findViewById<TextView>(R.id.widget_action)
            kotlin.test.assertTrue(action.height >= action.lineHeight, "the button is not squeezed")
            assertEquals(42, view.findViewById<ProgressBar>(R.id.widget_progress).progress)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val out = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(out, "widget-today.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
