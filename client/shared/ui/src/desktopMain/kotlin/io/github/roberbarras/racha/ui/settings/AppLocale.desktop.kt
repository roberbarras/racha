package io.github.roberbarras.racha.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

actual object LocalAppLocale {
    private val current = staticCompositionLocalOf { Locale.getDefault().toLanguageTag() }

    @Composable
    actual infix fun provides(language: String?): ProvidedValue<*> {
        // The system's language is the default when the app's UI was created (no state shared between windows).
        val system = remember { Locale.getDefault() }
        Locale.setDefault(language?.let(Locale::forLanguageTag) ?: system)
        return current provides Locale.getDefault().toLanguageTag()
    }
}
