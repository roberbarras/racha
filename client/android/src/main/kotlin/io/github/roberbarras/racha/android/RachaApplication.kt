package io.github.roberbarras.racha.android

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import io.github.roberbarras.racha.android.player.AndroidPlayerHooks

class RachaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        reportCrashes()
    }

    /** Uncaught errors go to the account's server (§8), then the system handles them as usual. */
    private fun reportCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { runBlocking { withTimeoutOrNull(2_000) { graph.telemetry.error(error, mapOf("thread" to thread.name)) } } }
            previous?.uncaughtException(thread, error)
        }
    }

    val graph: AndroidGraph by lazy { AndroidGraph(this) }

    /** App-scoped so a player outliving an activity recreation keeps working; bound to the visible activity. */
    val playerHooks: AndroidPlayerHooks by lazy { AndroidPlayerHooks(this) }
}
