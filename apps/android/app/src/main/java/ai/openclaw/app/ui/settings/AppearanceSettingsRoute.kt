package ai.openclaw.app.ui.settings

import ai.openclaw.app.AppLanguage
import ai.openclaw.app.AppearanceThemeMode
import ai.openclaw.app.appLanguageRowSubtitle
import ai.openclaw.app.currentAppLanguage
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.setAppLanguage
import ai.openclaw.app.settings.AppearanceSettingsFeature
import ai.openclaw.app.ui.design.ClawIconBadge
import ai.openclaw.app.ui.design.ClawListItem
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawSegmentedControl
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Runtime adapter for appearance settings. */
@Composable
internal fun AppearanceSettingsRoute(
  feature: AppearanceSettingsFeature,
  onBack: () -> Unit,
) {
  val themeMode by feature.themeMode.collectAsState()
  var appLanguage by remember { mutableStateOf(currentAppLanguage()) }

  SettingsDetailFrame(title = nativeString("Appearance"), subtitle = nativeString("Theme and interface language."), onBack = onBack) {
    SettingsMetricPanel(
      rows =
        listOf(
          SettingsMetric(nativeString("Theme"), appearanceThemeSummary(themeMode)),
          SettingsMetric(nativeString("Language"), appLanguageTitle(appLanguage)),
          SettingsMetric(nativeString("Contrast"), nativeString("High")),
          SettingsMetric(nativeString("Typography"), nativeString("Readable")),
        ),
    )
    ClawPanel {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = nativeString("Theme"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
        ClawSegmentedControl(
          options = appearanceThemeOptions(),
          selected = appearanceThemeSummary(themeMode),
          onSelect = { selected -> feature.actions.selectTheme(appearanceThemeModeForLabel(selected)) },
        )
      }
    }
    ClawPanel {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = nativeString("App language"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
        AppLanguage.entries.forEachIndexed { index, language ->
          if (index > 0) HorizontalDivider(color = ClawTheme.colors.border)
          AppLanguageRow(
            language = language,
            selected = language == appLanguage,
            onClick = {
              appLanguage = language
              setAppLanguage(language)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun AppLanguageRow(
  language: AppLanguage,
  selected: Boolean,
  onClick: () -> Unit,
) {
  ClawListItem(
    title = appLanguageTitle(language),
    subtitle = appLanguageRowSubtitle(language),
    leading = { ClawIconBadge(Icons.Default.Language) },
    trailing =
      if (selected) {
        {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = nativeString("Selected"),
            modifier = Modifier.size(18.dp),
            tint = ClawTheme.colors.primary,
          )
        }
      } else {
        null
      },
    onClick = onClick,
  )
}

private fun appLanguageTitle(language: AppLanguage): String = language.displayName

internal fun appearanceThemeSummary(mode: AppearanceThemeMode): String =
  when (mode) {
    AppearanceThemeMode.System -> nativeString("System")
    AppearanceThemeMode.Dark -> nativeString("Dark")
    AppearanceThemeMode.Light -> nativeString("Light")
  }

internal fun appearanceThemeOptions(): List<String> = AppearanceThemeMode.entries.map(::appearanceThemeSummary)

internal fun appearanceThemeModeForLabel(label: String): AppearanceThemeMode =
  AppearanceThemeMode.entries.firstOrNull { appearanceThemeSummary(it).equals(label.trim(), ignoreCase = true) }
    ?: AppearanceThemeMode.Dark
