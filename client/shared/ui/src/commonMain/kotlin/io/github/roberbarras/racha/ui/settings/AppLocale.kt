package io.github.roberbarras.racha.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key
import io.github.roberbarras.racha.domain.preferences.AppLanguage

/**
 * The app's language when it differs from the system's (spec §9 "idioma de la app"), following Compose
 * Multiplatform's runtime-locale recipe: each platform re-points what resources read, and the tree is rebuilt.
 */
expect object LocalAppLocale {
    @Composable
    infix fun provides(language: String?): ProvidedValue<*>
}

/** null: follow the system. */
fun AppLanguage.tag(): String? = when (this) {
    AppLanguage.SYSTEM -> null
    AppLanguage.ES -> "es"
    AppLanguage.EN -> "en"
}

@Composable
fun WithAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    val tag = language.tag()
    CompositionLocalProvider(LocalAppLocale provides tag) { key(tag) { content() } }
}
