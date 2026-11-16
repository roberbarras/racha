package io.github.roberbarras.racha.ui.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

actual object LocalAppLocale {
    /** The system's locale, kept while another language is chosen so "system" can go back to it. */
    private var system: Locale? = null

    @Composable
    actual infix fun provides(language: String?): ProvidedValue<*> {
        val locale = if (language == null) {
            (system ?: Locale.getDefault()).also { system = null }
        } else {
            if (system == null) system = Locale.getDefault()
            Locale.forLanguageTag(language)
        }
        Locale.setDefault(locale)
        val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
        val resources = LocalContext.current.resources
        @Suppress("DEPRECATION") // the documented way for Compose resources to follow an in-app language
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalConfiguration provides configuration
    }
}
