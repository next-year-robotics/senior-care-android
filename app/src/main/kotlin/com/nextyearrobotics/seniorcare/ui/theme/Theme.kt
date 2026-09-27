package com.nextyearrobotics.seniorcare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// dynamic color(Material You)는 브랜드 대비를 깨뜨리므로 쓰지 않는다. 다크 테마는 OPEN-D1.
private val LightColors = lightColorScheme(
    primary = DeepOcean,
    onPrimary = WarmWhite,
    secondaryContainer = PowderBlue,
    onSecondaryContainer = DeepOcean,
    background = Cream,
    onBackground = DeepOcean,
    surface = WarmWhite,
    onSurface = DeepOcean,
    surfaceContainerLow = WarmWhite,
    surfaceVariant = Beige,
    onSurfaceVariant = Slate,
    outlineVariant = Beige,
    error = BrandError,
    onError = Color.White,
)

@Composable
fun SeniorCareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = SeniorTypography, content = content)
}
