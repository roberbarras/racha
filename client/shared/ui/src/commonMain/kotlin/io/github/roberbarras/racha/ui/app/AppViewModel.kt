package io.github.roberbarras.racha.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.roberbarras.racha.application.browse.HomeView
import io.github.roberbarras.racha.application.library.scan.ScanStatus
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import io.github.roberbarras.racha.domain.preferences.AppLanguage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Appearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: String = Settings.AccentColor.default,
    val fontScale: Double = 1.0,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val background: String = "",
    val rail: String = "",
)

/** App-wide state shared by the shell (streak, categories, scan status) and the Home screen. */
class AppViewModel(private val deps: RachaDependencies) : ViewModel() {
    val home: StateFlow<HomeView?> = deps.observeHome().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scan: StateFlow<ScanStatus> = deps.keepLibraryUpToDate.status

    val storageAccess: StateFlow<Boolean> = deps.platform.storageAccess.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val appearance: StateFlow<Appearance> = combine(
        combine(
            deps.preferences.observe(Settings.Theme),
            deps.preferences.observe(Settings.AccentColor),
            deps.preferences.observe(Settings.FontScale),
            deps.preferences.observe(Settings.Language),
        ) { theme, accent, scale, language -> Appearance(theme, accent, scale, language) },
        deps.preferences.observe(Settings.BackgroundColor),
        deps.preferences.observe(Settings.RailColor),
    ) { base, background, rail -> base.copy(background = background, rail = rail) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())

    private val mutableOnboarding = MutableStateFlow<Boolean?>(null)

    /**
     * Whether to show the first-run guide: decided once at start (not done yet and nothing indexed), so adding the first
     * folder does not end it halfway. An existing library counts as done.
     */
    val onboarding: StateFlow<Boolean?> = mutableOnboarding.asStateFlow()

    init {
        viewModelScope.launch {
            val done = deps.preferences.get(Settings.OnboardingDone)
            val empty = !done && deps.observeHome().first().libraryIsEmpty
            if (!done && !empty) deps.preferences.set(Settings.OnboardingDone, true)
            mutableOnboarding.value = !done && empty
            io.github.roberbarras.racha.application.shared.AppLog.info("startup", "first screen: " + if (!done && empty) "welcome" else "home")
        }
    }

    fun finishOnboarding() {
        mutableOnboarding.value = false
    }

    fun setMode(mode: Mode) = viewModelScope.launch { deps.setMode(mode) }

    fun scanNow() = viewModelScope.launch {
        if (!scan.value.running) deps.keepLibraryUpToDate.scanNow()
    }

    fun requestStorageAccess() = deps.platform.requestStorageAccess()
}
