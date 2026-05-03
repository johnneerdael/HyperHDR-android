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
            primary = HyperHdrGreen,
            onPrimary = SurfaceBlack,
            primaryContainer = HyperHdrGreenDim,
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
