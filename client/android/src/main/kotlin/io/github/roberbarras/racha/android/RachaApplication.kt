package io.github.roberbarras.racha.android

import android.app.Application

class RachaApplication : Application() {
    val graph: AndroidGraph by lazy { AndroidGraph(this) }
}
