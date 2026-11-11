package io.github.roberbarras.racha.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.roberbarras.racha.application.environment.DescribeEnvironment
import io.github.roberbarras.racha.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Composition root: adapters are wired by hand, no reflection-based DI.
        val describeEnvironment = DescribeEnvironment { "Android ${Build.VERSION.RELEASE}" }
        setContent { App(greeting = describeEnvironment()) }
    }
}
