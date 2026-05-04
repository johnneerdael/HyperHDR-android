package eu.hyperhdr.android.tv.compose.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HyperHdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HyperHdrRed,
            onPrimary = SurfaceBlack,
            primaryContainer = HyperHdrRedDim,
            onPrimaryContainer = OnSurfaceWhite,
            background = SurfaceBlack,
            onBackground = OnSurfaceWhite,
            surface = SurfaceCharcoal,
            onSurface = OnSurfaceWhite,
            surfaceVariant = SurfaceCharcoal,
            onSurfaceVariant = OnSurfaceMuted,
            error = StatusError,
            onError = OnSurfaceWhite,
        ),
        // Wizard text fields use androidx.compose.material3.OutlinedTextField, which reads
        // from the *non-TV* MaterialTheme. Without this sibling theme, those controls fall
        // back to the M3 light-scheme defaults — dark text on our black background, plus a
        // purple focus ring instead of HyperHDR red. Keep the two palettes in sync.
        content = { Material3Sibling(content) },
    )
}

@Composable
private fun Material3Sibling(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = HyperHdrRed,
            onPrimary = SurfaceBlack,
            primaryContainer = HyperHdrRedDim,
            onPrimaryContainer = OnSurfaceWhite,
            background = SurfaceBlack,
            onBackground = OnSurfaceWhite,
            surface = SurfaceCharcoal,
            onSurface = OnSurfaceWhite,
            surfaceVariant = SurfaceCharcoal,
            onSurfaceVariant = OnSurfaceMuted,
            error = StatusError,
            onError = OnSurfaceWhite,
        ),
        content = content,
    )
}
