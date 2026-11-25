package eu.kanade.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R

@OptIn(ExperimentalTextApi::class)
private fun interFont(weight: FontWeight) = Font(
    resId = R.font.inter,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("opsz", 18f),
    ),
)

val YomuFontFamily = FontFamily(
    interFont(FontWeight.Normal),
    interFont(FontWeight.Medium),
    interFont(FontWeight.SemiBold),
    interFont(FontWeight.Bold),
)

private fun TextStyle.inter(tracking: Float = -0.1f) = copy(
    fontFamily = YomuFontFamily,
    letterSpacing = tracking.sp,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val YomuTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.inter(-1.2f),
        displayMedium = displayMedium.inter(-1f),
        displaySmall = displaySmall.inter(-0.8f),
        headlineLarge = headlineLarge.inter(-0.7f),
        headlineMedium = headlineMedium.inter(-0.6f),
        headlineSmall = headlineSmall.inter(-0.45f),
        titleLarge = titleLarge.inter(-0.35f),
        titleMedium = titleMedium.inter(-0.2f),
        titleSmall = titleSmall.inter(-0.1f),
        bodyLarge = bodyLarge.inter(-0.2f),
        bodyMedium = bodyMedium.inter(-0.1f),
        bodySmall = bodySmall.inter(0f),
        labelLarge = labelLarge.inter(-0.05f),
        labelMedium = labelMedium.inter(0f),
        labelSmall = labelSmall.inter(0f),
        displayLargeEmphasized = displayLargeEmphasized.inter(-1.2f),
        displayMediumEmphasized = displayMediumEmphasized.inter(-1f),
        displaySmallEmphasized = displaySmallEmphasized.inter(-0.8f),
        headlineLargeEmphasized = headlineLargeEmphasized.inter(-0.7f),
        headlineMediumEmphasized = headlineMediumEmphasized.inter(-0.6f),
        headlineSmallEmphasized = headlineSmallEmphasized.inter(-0.45f),
        titleLargeEmphasized = titleLargeEmphasized.inter(-0.35f),
        titleMediumEmphasized = titleMediumEmphasized.inter(-0.2f),
        titleSmallEmphasized = titleSmallEmphasized.inter(-0.1f),
        bodyLargeEmphasized = bodyLargeEmphasized.inter(-0.2f),
        bodyMediumEmphasized = bodyMediumEmphasized.inter(-0.1f),
        bodySmallEmphasized = bodySmallEmphasized.inter(0f),
        labelLargeEmphasized = labelLargeEmphasized.inter(-0.05f),
        labelMediumEmphasized = labelMediumEmphasized.inter(0f),
        labelSmallEmphasized = labelSmallEmphasized.inter(0f),
    )
}

internal val YomuShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)
