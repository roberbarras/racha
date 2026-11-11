package io.github.roberbarras.racha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Root composable shared by every platform. Design tokens replace these literals in H4. */
@Composable
fun App(greeting: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF14161A)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = greeting, style = TextStyle(color = Color(0xFFF2EFE9), fontSize = 28.sp))
    }
}
