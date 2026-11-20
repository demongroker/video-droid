package com.xerxes.videodroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared navy + gold tokens. Reference implementation lives in Hermes
 * (`app/hermes/updater/ui/HermesTheme.kt`); the hexes are documented in
 * `~/.hermes/plans/design-tokens-navy-gold.md`. Change them there first, then port.
 *
 * Gold never carries text on a light surface — the light scheme uses a darkened gold.
 */
object VdPalette {
  val NavyBase = Color(0xFF0B1220)
  val NavyElevated = Color(0xFF111A2B)
  val NavyCard = Color(0xFF1B2740)
  val NavyOutline = Color(0xFF2A3A57)

  val Gold = Color(0xFFD4AF37)
  val GoldOnLight = Color(0xFF7A5F14)
  val GoldContainerDark = Color(0xFF3A2E10)
  val GoldContainerLight = Color(0xFFF5E7BE)

  val TextOnNavy = Color(0xFFE6EDF7)
  val TextMutedOnNavy = Color(0xFFA9B6CC)
  val TextOnLight = Color(0xFF101828)
  val TextMutedOnLight = Color(0xFF48566E)

  val Success = Color(0xFF3FB950)
  val Warning = Color(0xFFD29922)
  val Danger = Color(0xFFF85149)
  val SuccessOnLight = Color(0xFF1A7F37)
  val WarningOnLight = Color(0xFF9A6700)
  val DangerOnLight = Color(0xFFB3261E)
}

/** Semantic colours Material's scheme has no slot for. Copy carries a tone, never a colour. */
data class VdTones(
  val success: Color,
  val warning: Color,
  val danger: Color,
  val muted: Color,
)

private val DarkTones = VdTones(
  VdPalette.Success, VdPalette.Warning, VdPalette.Danger, VdPalette.TextMutedOnNavy,
)
private val LightTones = VdTones(
  VdPalette.SuccessOnLight, VdPalette.WarningOnLight, VdPalette.DangerOnLight,
  VdPalette.TextMutedOnLight,
)

val LocalVdTones = staticCompositionLocalOf { DarkTones }

private val DarkScheme = darkColorScheme(
  primary = VdPalette.Gold,
  onPrimary = VdPalette.NavyBase,
  primaryContainer = VdPalette.GoldContainerDark,
  onPrimaryContainer = Color(0xFFF0DFA8),
  secondary = Color(0xFF8FA3C8),
  onSecondary = VdPalette.NavyBase,
  background = VdPalette.NavyBase,
  onBackground = VdPalette.TextOnNavy,
  surface = VdPalette.NavyElevated,
  onSurface = VdPalette.TextOnNavy,
  surfaceVariant = VdPalette.NavyCard,
  onSurfaceVariant = VdPalette.TextMutedOnNavy,
  error = VdPalette.Danger,
  onError = VdPalette.NavyBase,
  outline = VdPalette.NavyOutline,
)

private val LightScheme = lightColorScheme(
  primary = VdPalette.GoldOnLight,
  onPrimary = Color.White,
  primaryContainer = VdPalette.GoldContainerLight,
  onPrimaryContainer = VdPalette.GoldContainerDark,
  secondary = Color(0xFF41547A),
  onSecondary = Color.White,
  background = Color(0xFFF7F8FA),
  onBackground = VdPalette.TextOnLight,
  surface = Color.White,
  onSurface = VdPalette.TextOnLight,
  surfaceVariant = Color(0xFFEEF1F6),
  onSurfaceVariant = VdPalette.TextMutedOnLight,
  error = VdPalette.DangerOnLight,
  onError = Color.White,
  outline = Color(0xFFC7CFDB),
)

private val VdShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(16.dp),
  extraLarge = RoundedCornerShape(24.dp),
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
  TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.35).sp,
    letterSpacing = tracking.sp,
  )

private val VdTypography = Typography(
  headlineMedium = style(28, FontWeight.SemiBold, -0.2),
  headlineSmall = style(24, FontWeight.SemiBold),
  titleLarge = style(20, FontWeight.SemiBold),
  titleMedium = style(18, FontWeight.SemiBold),
  titleSmall = style(16, FontWeight.SemiBold),
  bodyLarge = style(16),
  bodyMedium = style(14),
  bodySmall = style(12),
  labelLarge = style(14, FontWeight.SemiBold, 0.1),
  labelMedium = style(12, FontWeight.Medium, 0.2),
  labelSmall = style(11, FontWeight.Medium, 0.3),
)

@Composable
fun VideoDroidTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(LocalVdTones provides if (darkTheme) DarkTones else LightTones) {
    MaterialTheme(
      colorScheme = if (darkTheme) DarkScheme else LightScheme,
      typography = VdTypography,
      shapes = VdShapes,
      content = content,
    )
  }
}
