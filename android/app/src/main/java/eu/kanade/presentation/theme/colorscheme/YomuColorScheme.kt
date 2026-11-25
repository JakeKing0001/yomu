package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal object YomuColorScheme : BaseColorScheme() {

    override val darkScheme: ColorScheme = TachiyomiColorScheme.darkScheme.copy(
        primary = Color(0xFF8CB5FF),
        onPrimary = Color(0xFF04203F),
        primaryContainer = Color(0xFF1E3A63),
        onPrimaryContainer = Color(0xFFD8E6FF),
        inversePrimary = Color(0xFF2F6FE0),
        secondary = Color(0xFF8CB5FF),
        onSecondary = Color(0xFF04203F),
        secondaryContainer = Color(0xFF1C2C45),
        onSecondaryContainer = Color(0xFFD8E6FF),
        tertiary = Color(0xFF7BE0A0),
        onTertiary = Color(0xFF00391C),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1C1C1E),
        onSurfaceVariant = Color(0xFFAEAEB2),
        surfaceTint = Color(0xFF8CB5FF),
        outline = Color(0xFF636366),
        outlineVariant = Color(0xFF2C2C2E),
        surfaceContainerLowest = Color(0xFF0B0B0C),
        surfaceContainerLow = Color(0xFF141415),
        surfaceContainer = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF242426),
        surfaceContainerHighest = Color(0xFF2C2C2E),
    )

    override val lightScheme: ColorScheme = TachiyomiColorScheme.lightScheme.copy(
        primary = Color(0xFF2F6FE0),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF2F6FE0),
        onSecondary = Color(0xFFFFFFFF),
        background = Color(0xFFF2F2F7),
        surface = Color(0xFFF2F2F7),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFAFAFC),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFF7F7FA),
        surfaceContainerHighest = Color(0xFFEDEDF2),
    )
}
