package io.github.roberbarras.racha.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

actual object LocalAppLocale {
    private val current = staticCompositionLocalOf { Locale.getDefault().toLanguageTag() }

    /** The system's locale, kept while another language is chosen so "system" can go back to it. */
    private var system: Locale? = null

    @Composable
    actual infix fun provides(language: String?): ProvidedValue<*> {
        if (language == null) {
            system?.let { Locale.setDefault(it) }
            system = null
        } else {
            if (system == null) system = Locale.getDefault()
            Locale.setDefault(Locale.forLanguageTag(language))
        }
        return current provides Locale.getDefault().toLanguageTag()
    }
}
