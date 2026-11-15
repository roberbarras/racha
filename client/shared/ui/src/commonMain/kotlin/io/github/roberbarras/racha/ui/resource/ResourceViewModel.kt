package io.github.roberbarras.racha.ui.resource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.roberbarras.racha.application.resource.ResourceState
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.app.RachaDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ResourceViewModel(private val deps: RachaDependencies, element: ContentId) : ViewModel() {
    private val viewer = deps.resourceViewer()
    val state: StateFlow<ResourceState> = viewer.state
    val sidebar = MutableStateFlow(false)

    init {
        viewModelScope.launch { viewer.open(element) }
    }

    fun setWatched(watched: Boolean) = viewModelScope.launch { viewer.setWatched(watched) }

    override fun onCleared() {
        // Marks the resource as seen and records the visit, after the screen is gone.
        deps.appScope.launch { viewer.close() }
    }
}
