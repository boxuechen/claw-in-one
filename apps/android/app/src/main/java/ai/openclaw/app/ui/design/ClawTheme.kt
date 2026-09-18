package ai.openclaw.app.ui.design

import ai.openclaw.app.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val clawFontFamily =
  FontFamily(
    Font(resId = R.font.manrope_400_regular, weight = FontWeight.Normal),
    Font(resId = R.font.manrope_500_medium, weight = FontWeight.Medium),
    Font(resId = R.font.manrope_600_semibold, weight = FontWeight.SemiBold),
    Font(resId = R.font.manrope_700_bold, weight = FontWeight.Bold),
  )

/**
 * App color tokens consumed by ClawTheme and bridged into Material components.
 */
@Immutable
internal data class ClawColors(
  val canvas: Color,
  val surface: Color,
  val surfaceRaised: Color,
  val surfacePressed: Color,
  val floatingSurface: Color,
  val accent: Color,
  val accentSoft: Color,
  val accentBorder: Color,
  val border: Color,
  val borderStrong: Color,
  val text: Color,
  val textMuted: Color,
  val textSubtle: Color,
  val primary: Color,
  val primaryText: Color,
  val success: Color,
  val successSoft: Color,
  val warning: Color,
  val warningSoft: Color,
  val danger: Color,
  val dangerSoft: Color,
  val codeBg: Color,
  val codeText: Color,
  val codeBorder: Color,
)

/**
 * App spacing scale for Compose screens and shared controls.
 */
@Immutable
internal data class ClawSpacing(
  val xxxs: Dp = 4.dp,
  val xxs: Dp = 8.dp,
  val xs: Dp = 12.dp,
  val sm: Dp = 16.dp,
  val md: Dp = 20.dp,
  val lg: Dp = 24.dp,
  val xl: Dp = 32.dp,
  val xxl: Dp = 40.dp,
)

/**
 * Shared control metrics. These are intentionally separate from layout spacing
 * so screens cannot accidentally treat accessibility sizes as visual gaps.
 */
@Immutable
internal data class ClawSizes(
  val minimumTouchTarget: Dp = 48.dp,
  val floatingControl: Dp = 48.dp,
  val compactIcon: Dp = 18.dp,
  val standardIcon: Dp = 20.dp,
  val prominentIcon: Dp = 21.dp,
  val statusDot: Dp = 5.dp,
)

/**
 * Radius scale for rows, panels, controls, sheets, and status pills.
 */
@Immutable
internal data class ClawRadii(
  val row: Dp = 16.dp,
  val panel: Dp = 24.dp,
  val control: Dp = 20.dp,
  val button: Dp = 24.dp,
  val sheet: Dp = 28.dp,
  val pill: Dp = 999.dp,
)

/**
 * App text styles kept independent from Material typography names.
 */
@Immutable
internal data class ClawTypography(
  val display: TextStyle,
  val title: TextStyle,
  val section: TextStyle,
  val body: TextStyle,
  val label: TextStyle,
  val caption: TextStyle,
  val captionSmall: TextStyle,
  val mono: TextStyle,
)

private val ClawDarkColors =
  ClawColors(
    canvas = Color.Black,
    surface = Color(0xFF212121),
    surfaceRaised = Color(0xFF2B2B2B),
    surfacePressed = Color(0xFF3A3A3A),
    floatingSurface = Color(0xFF202020),
    accent = Color(0xFF2563EB),
    accentSoft = Color(0xFF172554),
    accentBorder = Color(0xFF3B82F6),
    border = Color(0xFF343434),
    borderStrong = Color(0xFF4A4A4A),
    text = Color(0xFFF4F4F4),
    textMuted = Color(0xFFA6A6A6),
    textSubtle = Color(0xFF777777),
    primary = Color(0xFFFFFFFF),
    primaryText = Color(0xFF050505),
    success = Color(0xFF3EDB82),
    successSoft = Color(0xFF102719),
    warning = Color(0xFFE6B956),
    warningSoft = Color(0xFF2B2412),
    danger = Color(0xFFFF6B6B),
    dangerSoft = Color(0xFF2C1414),
    codeBg = Color(0xFF111317),
    codeText = Color(0xFFE8EAEE),
    codeBorder = Color(0xFF2B2E35),
  )

private val ClawLightColors =
  ClawColors(
    canvas = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F2),
    surfaceRaised = Color(0xFFFFFFFF),
    surfacePressed = Color(0xFFE5E5E5),
    floatingSurface = Color(0xFFF4F4F4),
    accent = Color(0xFF1B5ACB),
    accentSoft = Color(0xFFEAF2FF),
    accentBorder = Color(0xFF174CA9),
    border = Color(0xFFE2E2E2),
    borderStrong = Color(0xFFCCCCCC),
    text = Color(0xFF171717),
    textMuted = Color(0xFF666666),
    textSubtle = Color(0xFF929292),
    primary = Color(0xFF111827),
    primaryText = Color(0xFFFFFFFF),
    success = Color(0xFF217747),
    successSoft = Color(0xFFE9F7EF),
    warning = Color(0xFFA56F17),
    warningSoft = Color(0xFFFFF3DC),
    danger = Color(0xFFB82929),
    dangerSoft = Color(0xFFFFE9E9),
    codeBg = Color(0xFFEFF3F8),
    codeText = Color(0xFF172033),
    codeBorder = Color(0xFFD7DDE7),
  )

internal fun clawColorsForTheme(
  dark: Boolean,
  accentArgb: Long?,
): ClawColors {
  val base = if (dark) ClawDarkColors else ClawLightColors
  val accent = accentArgb?.let(::Color) ?: return base
  return base.copy(
    accent = accent,
    accentSoft = accent.copy(alpha = if (dark) 0.25f else 0.08f).compositeOver(base.canvas),
    accentBorder = lerp(accent, Color.Black, 0.12f),
  )
}

private val LocalClawColors = staticCompositionLocalOf { ClawDarkColors }
private val LocalClawSpacing = staticCompositionLocalOf { ClawSpacing() }
private val LocalClawSizes = staticCompositionLocalOf { ClawSizes() }
private val LocalClawRadii = staticCompositionLocalOf { ClawRadii() }
private val LocalClawTypography = staticCompositionLocalOf { clawTypography(clawFontFamily) }

/**
 * Composition-local access point for OpenClaw Android design tokens.
 */
internal object ClawTheme {
  val colors: ClawColors
    @Composable
    @ReadOnlyComposable
    get() = LocalClawColors.current

  val spacing: ClawSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalClawSpacing.current

  val sizes: ClawSizes
    @Composable
    @ReadOnlyComposable
    get() = LocalClawSizes.current

  val radii: ClawRadii
    @Composable
    @ReadOnlyComposable
    get() = LocalClawRadii.current

  val type: ClawTypography
    @Composable
    @ReadOnlyComposable
    get() = LocalClawTypography.current
}

/**
 * Low-level design-system provider installed by the app theme and isolated previews/tests.
 */
@Composable
internal fun ProvideClawDesignSystem(
  dark: Boolean = true,
  accentArgb: Long? = null,
  content: @Composable () -> Unit,
) {
  val colors = clawColorsForTheme(dark = dark, accentArgb = accentArgb)
  val typography = clawTypography(clawFontFamily)

  CompositionLocalProvider(
    LocalClawColors provides colors,
    LocalClawSpacing provides ClawSpacing(),
    LocalClawSizes provides ClawSizes(),
    LocalClawRadii provides ClawRadii(),
    LocalClawTypography provides typography,
  ) {
    MaterialTheme(
      colorScheme = clawMaterialColorScheme(colors, dark),
      typography = materialTypography(typography),
      shapes = Shapes(),
      content = content,
    )
  }
}

private fun clawTypography(fontFamily: FontFamily) =
  ClawTypography(
    display =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
      ),
    title =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.sp,
      ),
    section =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
      ),
    body =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
      ),
    label =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
      ),
    caption =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
      ),
    captionSmall =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
      ),
    mono =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
      ),
  )

private fun materialTypography(type: ClawTypography) =
  Typography(
    displayMedium = type.display,
    titleLarge = type.title,
    titleMedium = type.section,
    bodyLarge = type.body,
    labelLarge = type.label,
    labelSmall = type.caption,
  )

private fun clawMaterialColorScheme(
  colors: ClawColors,
  dark: Boolean,
) = if (dark) {
  darkColorScheme(
    primary = colors.primary,
    onPrimary = colors.primaryText,
    background = colors.canvas,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    outline = colors.border,
    error = colors.danger,
    onError = colors.primaryText,
  )
} else {
  lightColorScheme(
    primary = colors.primary,
    onPrimary = colors.primaryText,
    background = colors.canvas,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    outline = colors.border,
    error = colors.danger,
    onError = colors.primaryText,
  )
}
