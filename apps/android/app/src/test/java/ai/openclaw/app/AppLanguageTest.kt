package ai.openclaw.app

import ai.openclaw.app.approval.ApprovalFailure
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.i18n.joinedNativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.resolveNativeText
import ai.openclaw.app.i18n.verbatimText
import ai.openclaw.app.node.NodePresenceAliveBeacon
import ai.openclaw.app.ui.approval.approvalFailureText
import ai.openclaw.app.ui.chat.contextMeterThinkingLabel
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLanguageTest {
  @Test
  fun supportedLanguagesMatchPackagedTranslations() {
    assertEquals(
      setOf(
        "en",
        "zh-CN",
      ),
      AppLanguage.entries.mapNotNull(AppLanguage::languageTag).toSet(),
    )
  }

  @Test
  fun everyLanguageRoundTripsThroughAndroidLocales() {
    AppLanguage.entries.forEach { language ->
      assertEquals(language, appLanguageFromLocales(localesForAppLanguage(language)))
    }
  }

  @Test
  fun defaultLanguageIsEnglish() {
    assertFalse(localesForAppLanguage(AppLanguage.English).isEmpty)
    assertEquals(AppLanguage.English, appLanguageFromLocales(LocaleListCompat.getEmptyLocaleList()))
  }

  @Test
  fun unsetInstallationIsPinnedToEnglish() {
    val app = RuntimeEnvironment.getApplication()
    NativeStringResources.install(app)
    NativeStringResources.applyPlatformApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    NativeStringResources.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    assertFalse(NativeStringResources.hasExplicitApplicationLocale())

    ensureDefaultAppLanguage(app)

    assertTrue(NativeStringResources.hasExplicitApplicationLocale())
    assertEquals("en", LocaleManagerCompat.getApplicationLocales(app).toLanguageTags())
  }

  @Test
  fun languageTagsNormalizeAtThePlatformBoundary() {
    assertEquals(AppLanguage.English, AppLanguage.fromLanguageTag("en-US"))
    assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromLanguageTag("zh-Hans-SG"))
    assertEquals(AppLanguage.English, AppLanguage.fromLanguageTag("de-DE"))
    assertEquals(AppLanguage.English, AppLanguage.fromLanguageTag(null))
  }

  @Test
  fun requestedLocaleListUsesTheFirstSupportedLanguage() {
    assertEquals(
      AppLanguage.ChineseSimplified,
      appLanguageFromLocales(LocaleListCompat.forLanguageTags("xx,zh-Hans-SG,en-US")),
    )
  }

  @Test
  fun generatedLocaleConfigMatchesPickerLanguages() {
    val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml._generated_res_locale_config)
    val packagedTags = mutableSetOf<String?>()
    var defaultLocaleTag: String? = null
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG) {
        when (parser.name) {
          "locale-config" -> defaultLocaleTag = parser.getAttributeValue(androidNamespace, "defaultLocale")
          "locale" -> packagedTags += AppLanguage.fromLanguageTag(parser.getAttributeValue(androidNamespace, "name")).languageTag
        }
      }
      parser.next()
    }

    assertEquals("en", defaultLocaleTag)
    assertEquals(AppLanguage.entries.mapNotNull(AppLanguage::languageTag).toSet(), packagedTags)
  }

  @Test
  fun everyPickerOptionHasAUniqueLabel() {
    val labels = AppLanguage.entries.map(AppLanguage::displayName)
    assertFalse(labels.any(String::isBlank))
    assertEquals(labels.size, labels.toSet().size)
  }

  @Test
  fun languageSubtitleReportsTheSelectedLocale() {
    assertEquals("ClawInOne interface · en", appLanguageRowSubtitle(AppLanguage.English))
    assertEquals("ClawInOne interface · zh-CN", appLanguageRowSubtitle(AppLanguage.ChineseSimplified))
  }

  @Test
  fun retainedNativeTextResolvesAgainstTheCurrentLocale() {
    val activity = Robolectric.buildActivity(LocaleTestActivity::class.java).setup()
    NativeStringResources.install(activity.get())
    val retained = MutableStateFlow(nativeText("Appearance")).resolveNativeText()
    val retainedComposite =
      MutableStateFlow(
        joinedNativeText(
          separator = " · ",
          parts = listOf(nativeText("Appearance"), verbatimText("raw")),
        ),
      ).resolveNativeText()
    val previous = currentAppLanguage()
    try {
      setAppLanguage(AppLanguage.English)
      assertEquals("Appearance", retained.value)
      assertEquals("Appearance · raw", retainedComposite.value)

      setAppLanguage(AppLanguage.ChineseSimplified)
      assertEquals("外观", retained.value)
      assertEquals("外观 · raw", retainedComposite.value)
      assertEquals("高", contextMeterThinkingLabel("high"))
      assertEquals("adaptive", contextMeterThinkingLabel("adaptive"))
      val androidRelease =
        Build.VERSION.RELEASE
          ?.trim()
          .orEmpty()
          .ifEmpty { "unknown" }
      assertEquals(
        "Android $androidRelease (SDK ${Build.VERSION.SDK_INT})",
        NodePresenceAliveBeacon.androidPlatformMetadata(),
      )
      assertEquals("正在连接…", gatewayConnectionStatusForDisplay("Connecting…"))
      assertEquals(
        "无法加载审批请求。",
        approvalFailureText(ApprovalFailure.LoadInbox),
      )
    } finally {
      setAppLanguage(previous)
      activity.destroy()
    }
  }

  private companion object {
    const val androidNamespace = "http://schemas.android.com/apk/res/android"
  }
}

private class LocaleTestActivity : AppCompatActivity()
