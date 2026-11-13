package io.github.roberbarras.racha.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.roberbarras.racha.application.browse.HomeView
import io.github.roberbarras.racha.application.library.scan.ScanStatus
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Appearance(val theme: ThemeMode = ThemeMode.SYSTEM, val accent: String = Settings.AccentColor.default, val fontScale: Double = 1.0)

/** App-wide state shared by the shell (streak, categories, scan status) and the Home screen. */
class AppViewModel(private val deps: RachaDependencies) : ViewModel() {
    val home: StateFlow<HomeView?> = deps.observeHome().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scan: StateFlow<ScanStatus> = deps.keepLibraryUpToDate.status

    val storageAccess: StateFlow<Boolean> = deps.platform.storageAccess.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val appearance: StateFlow<Appearance> = combine(
        deps.preferences.observe(Settings.Theme),
        deps.preferences.observe(Settings.AccentColor),
        deps.preferences.observe(Settings.FontScale),
    ) { theme, accent, scale -> Appearance(theme, accent, scale) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Appearance())

    fun setMode(mode: Mode) = viewModelScope.launch { deps.setMode(mode) }

    fun scanNow() = viewModelScope.launch {
        if (!scan.value.running) deps.keepLibraryUpToDate.scanNow()
    }

    fun requestStorageAccess() = deps.platform.requestStorageAccess()
}
