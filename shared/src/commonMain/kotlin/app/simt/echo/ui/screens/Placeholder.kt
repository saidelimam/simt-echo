package app.simt.echo.ui.screens

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Placeholder screen state. Owning slices (W1-03, W2-01, W2-04, W2-06, ...)
 * replace both the UI text and the state shape; the ViewModel seam —
 * one `ui: StateFlow<XUi>` per screen — stays.
 */
data class PlaceholderUi(
    val title: String,
    val subtitle: String,
)

/** Generic placeholder ViewModel; split per screen when the screen lands. */
class PlaceholderViewModel(title: String, subtitle: String) : ViewModel() {
    private val _ui = MutableStateFlow(PlaceholderUi(title, subtitle))
    val ui: StateFlow<PlaceholderUi> = _ui.asStateFlow()
}
