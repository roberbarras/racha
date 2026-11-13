package io.github.roberbarras.racha.android

import android.app.Application
import io.github.roberbarras.racha.android.player.AndroidPlayerHooks

class RachaApplication : Application() {
    val graph: AndroidGraph by lazy { AndroidGraph(this) }

    /** App-scoped so a player outliving an activity recreation keeps working; bound to the visible activity. */
    val playerHooks: AndroidPlayerHooks by lazy { AndroidPlayerHooks(this) }
}
