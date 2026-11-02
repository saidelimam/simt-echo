package app.simt.echo.ui.atmosphere

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// Canopy-glass surface, freeze §4 + plan §5 Surfaces row: void stays
// continuous, 1 dp `line` borders, horizon top-light only on
// dialogs/sheets/floating surfaces per variant table, top light never at
// full intensity at a corner, flat fallback without blur. Shapes and line
// colors are reused from the frozen theme (no new tokens): Dialog/Sheet
// use the overlay shape (28 dp), Floating/Flat the record shape (20 dp).

/** Which canopy treatment a surface gets. Only [Flat] skips the top-light. */
enum class CanopyVariant {
    Dialog,
    Sheet,
    Floating,
    Flat,
}

val CanopyVariant.hasTopLight: Boolean
    get() = this != CanopyVariant.Flat

/**
 * Canopy glass as a modifier: translucent surface + 1 dp line border,
 * clipped to [shape]. Deliberately no real-time blur — translucency reads
 * as glass at a fraction of the GPU cost.
 */
@Composable
fun Modifier.canopyGlass(
    variant: CanopyVariant,
    shape: Shape? = null,
    surfaceAlpha: Float = 0.90f,
): Modifier {
    val colorScheme = MaterialTheme.colorScheme
    val resolved = shape ?: defaultCanopyShape(variant)
    val background = colorScheme.surface.copy(alpha = if (variant == CanopyVariant.Flat) 1f else surfaceAlpha)
    return this
        .clip(resolved)
        .background(background)
        .border(1.dp, colorScheme.outlineVariant, resolved)
}

@Composable
private fun defaultCanopyShape(variant: CanopyVariant): Shape {
    val shapes = MaterialTheme.shapes
    return when (variant) {
        CanopyVariant.Dialog, CanopyVariant.Sheet -> shapes.extraLarge
        CanopyVariant.Floating, CanopyVariant.Flat -> shapes.large
    }
}

/**
 * Canopy panel with the variant horizon top-light: a short vertical
 * gradient strip pinned to the top edge. Peak alpha stays low and the
 * strip fades before any corner, so the light never touches a corner at
 * full intensity. [Flat] renders the same Box hierarchy without the strip.
 */
@Composable
fun CanopyPanel(
    variant: CanopyVariant,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    content: @Composable () -> Unit,
) {
    val resolved = shape ?: defaultCanopyShape(variant)
    val topLight = if (variant.hasTopLight) {
        remember {
            Brush.verticalGradient(
                // Peak 0.08 well below full intensity; transparent at both
                // ends so corners never catch the light.
                0.0f to Color.White.copy(alpha = 0f),
                0.25f to Color.White.copy(alpha = 0.08f),
                1.0f to Color.White.copy(alpha = 0f),
            )
        }
    } else {
        null
    }
    Box(modifier = modifier.canopyGlass(variant, resolved)) {
        content()
        if (topLight != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(resolved)
                    .background(topLight),
            )
        }
    }
}
