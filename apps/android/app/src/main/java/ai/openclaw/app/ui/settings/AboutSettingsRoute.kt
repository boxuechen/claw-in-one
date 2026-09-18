package ai.openclaw.app.ui.settings

import ai.openclaw.app.AndroidLicenseNotice
import ai.openclaw.app.BuildConfig
import ai.openclaw.app.R
import ai.openclaw.app.bootstrap.BOOTSTRAP_HANDOFF_PROTOCOL_VERSION
import ai.openclaw.app.bootstrap.PINNED_OPENCLAW_RELEASE
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.loadAndroidLicenseNotices
import ai.openclaw.app.settings.AboutSettingsFeature
import ai.openclaw.app.ui.design.ClawListItem
import ai.openclaw.app.ui.design.ClawListPanel
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Runtime adapter for About settings. */
@Composable
internal fun AboutSettingsRoute(
  feature: AboutSettingsFeature,
  onOpenLicenses: () -> Unit,
  onPreviewOnboarding: (() -> Unit)?,
  onBack: () -> Unit,
) {
  val gatewayVersion by feature.gatewayVersion.collectAsState()
  val updateAvailable by feature.updateAvailable.collectAsState()
  val latestVersion = updateAvailable?.latestVersion?.takeIf { it.isNotBlank() }
  val currentGatewayVersion = updateAvailable?.currentVersion?.takeIf { it.isNotBlank() } ?: gatewayVersion
  val appLocale = LocalConfiguration.current.locales[0]

  SettingsDetailFrame(title = nativeString("About"), subtitle = nativeString("OpenClaw on one Android device."), onBack = onBack) {
    AboutHeroPanel()
    AboutBuildIdentityPanel(
      versionName = BuildConfig.VERSION_NAME,
      versionCode = BuildConfig.VERSION_CODE,
      gitCommit = BuildConfig.GIT_COMMIT,
      buildTimestamp = BuildConfig.BUILD_TIMESTAMP,
      locale = appLocale,
    )
    SettingsMetricPanel(
      rows =
        listOf(
          SettingsMetric(nativeString("Channel"), androidDistributionChannel()),
          SettingsMetric(nativeString("OpenClaw Runtime"), PINNED_OPENCLAW_RELEASE.version),
          SettingsMetric(nativeString("Bootstrap Protocol"), "v$BOOTSTRAP_HANDOFF_PROTOCOL_VERSION"),
          SettingsMetric(nativeString("Running Gateway"), currentGatewayVersion ?: nativeString("Not connected")),
        ),
    )
    ClawPanel {
      Text(text = aboutUpdateText(latestVersion = latestVersion), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
    ClawPanel(contentPadding = PaddingValues(0.dp)) {
      ClawListItem(
        title = nativeString("Licenses"),
        subtitle = nativeString("Open-source notices"),
        onClick = onOpenLicenses,
        trailing = {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = nativeString("Open Licenses"),
            modifier = Modifier.size(20.dp),
            tint = ClawTheme.colors.text,
          )
        },
      )
    }
    onPreviewOnboarding?.let { openReview ->
      ClawPanel(contentPadding = PaddingValues(0.dp)) {
        ClawListItem(
          title = nativeString("Preview first setup"),
          subtitle = nativeString("Preview only. Your device will not be changed."),
          onClick = openReview,
          trailing = {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = nativeString("Open first setup preview"),
              modifier = Modifier.size(20.dp),
              tint = ClawTheme.colors.text,
            )
          },
        )
      }
    }
    AboutLinksPanel()
    Text(
      text = nativeString("ClawInOne is an independent project based on OpenClaw."),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textSubtle,
      modifier = Modifier.fillMaxWidth(),
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun AboutHeroPanel() {
  ClawPanel {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Image(
        painter = painterResource(R.drawable.clawinone_logo),
        contentDescription = nativeString("ClawInOne logo"),
        modifier = Modifier.size(96.dp),
      )
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = nativeString("ClawInOne"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text(text = nativeString("OpenClaw on one Android device"), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }
    }
  }
}

/** External project links; static first-party URLs matching the iOS and macOS About screens. */
private data class AboutLink(
  val title: String,
  val subtitle: String,
  val url: String,
)

private val aboutLinks =
  listOf(
    AboutLink("Website", "openclaw.ai", "https://openclaw.ai"),
    AboutLink("Docs", "docs.openclaw.ai", "https://docs.openclaw.ai"),
    AboutLink("GitHub", "github.com/openclaw/openclaw", "https://github.com/openclaw/openclaw"),
    AboutLink("Discord", "discord.gg/clawd", "https://discord.gg/clawd"),
  )

@Composable
private fun AboutLinksPanel() {
  val uriHandler = LocalUriHandler.current
  ClawListPanel(items = aboutLinks) { link ->
    ClawListItem(
      title = aboutLinkTitle(link.title),
      subtitle = link.subtitle,
      onClick = { uriHandler.openUri(link.url) },
      trailing = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.OpenInNew,
          contentDescription = null,
          tint = ClawTheme.colors.textSubtle,
          modifier = Modifier.size(16.dp),
        )
      },
    )
  }
}

@Composable
internal fun LicensesSettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val licenses = remember(context) { loadAndroidLicenseNotices(context.assets) }
  var selectedLicense by remember { mutableStateOf<AndroidLicenseNotice?>(null) }
  val backToListOrSettings = {
    if (selectedLicense == null) {
      onBack()
    } else {
      selectedLicense = null
    }
  }

  BackHandler(enabled = selectedLicense != null) {
    selectedLicense = null
  }

  SettingsDetailFrame(
    title = nativeString("Licenses"),
    subtitle = if (selectedLicense == null) nativeString("OpenClaw appreciates its partners in the open-source community.") else "",
    subtitleTextAlign = TextAlign.Center,
    onBack = backToListOrSettings,
  ) {
    val selected = selectedLicense
    if (selected == null) {
      if (licenses.isEmpty()) {
        ClawPanel {
          Text(text = nativeString("No license notices are packaged in this build."), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
        }
      } else {
        ClawListPanel(items = licenses) { license ->
          LicenseListRow(license = license, onClick = { selectedLicense = license })
        }
      }
    } else {
      ClawPanel {
        Text(text = selected.text, style = ClawTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = ClawTheme.colors.textMuted)
      }
    }
  }
}

@Composable
private fun LicenseListRow(
  license: AndroidLicenseNotice,
  onClick: () -> Unit,
) {
  ClawListItem(
    title = license.title,
    onClick = onClick,
    trailing = {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = nativeString("Open \${license.title}", license.title),
        modifier = Modifier.size(20.dp),
        tint = ClawTheme.colors.text,
      )
    },
  )
}

internal fun androidDistributionChannel(flavor: String = BuildConfig.FLAVOR): String =
  when (flavor.trim()) {
    "play" -> "Play"
    "thirdParty" -> nativeString("Third-party")
    "" -> nativeString("Unknown")
    else -> flavor.trim()
  }

internal fun aboutLinkTitle(title: String): String =
  when (title) {
    "Website" -> nativeString("Website")
    "Docs" -> nativeString("Docs")
    else -> title
  }

/** Chooses about-screen copy based on whether the gateway advertises an update. */
private fun aboutUpdateText(latestVersion: String?): String =
  if (latestVersion == null) {
    nativeString("OpenClaw on one Android device.")
  } else {
    nativeString("A Gateway update is available. Run the update from the Web UI or CLI when you are ready.")
  }
