package io.github.roberbarras.racha.ui.settings

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

actual object LocalAppLocale {
    @Composable
    actual infix fun provides(language: String?): ProvidedValue<*> {
        // The system's language is always the system configuration's, whatever the app set before.
        val locale = language?.let(Locale::forLanguageTag) ?: Resources.getSystem().configuration.locales.get(0)
        Locale.setDefault(locale)
        val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
        val resources = LocalContext.current.resources
        @Suppress("DEPRECATION") // the documented way for Compose resources to follow an in-app language
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalConfiguration provides configuration
    }
}
