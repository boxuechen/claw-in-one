package ai.openclaw.app.ui

import ai.openclaw.app.AppearanceThemeMode
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal val LocalResolvedAppearanceIsDark = staticCompositionLocalOf { false }

/**
 * Single application theme entry point for appearance, design tokens, Material controls, and system bars.
 */
@Composable
fun OpenClawTheme(
  themeMode: AppearanceThemeMode = AppearanceThemeMode.Dark,
  accentArgb: Long? = null,
  content: @Composable () -> Unit,
) {
  val isDark = themeMode.isDark(systemDark = isSystemInDarkTheme())

  OpenClawSystemBarAppearance(lightAppearance = !isDark)

  CompositionLocalProvider(
    LocalResolvedAppearanceIsDark provides isDark,
  ) {
    SystemAnimationsProvider {
      ProvideClawDesignSystem(dark = isDark, accentArgb = accentArgb, content = content)
    }
  }
}

@Composable
private fun OpenClawSystemBarAppearance(lightAppearance: Boolean) {
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window ?: return@SideEffect
      WindowCompat
        .getInsetsController(window, window.decorView)
        .isAppearanceLightStatusBars = lightAppearance
      WindowCompat
        .getInsetsController(window, window.decorView)
        .isAppearanceLightNavigationBars = lightAppearance
    }
  }
}
