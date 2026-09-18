package ai.openclaw.app

import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.i18n.notifyNativeLocaleChanged
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Keep these tags aligned with androidResources.localeFilters so Android never offers an unsupported locale. */
internal enum class AppLanguage(
  val languageTag: String,
  val displayName: String,
) {
  English(languageTag = "en", displayName = "English"),
  ChineseSimplified(languageTag = "zh-CN", displayName = "简体中文"),
  ;

  companion object {
    fun fromLanguageTag(languageTag: String?): AppLanguage {
      val locale = languageTag?.trim()?.takeIf(String::isNotEmpty)?.let(Locale::forLanguageTag)
      return locale?.let(::fromLocale) ?: English
    }

    internal fun fromLocale(locale: Locale): AppLanguage? {
      val exactTag = locale.toLanguageTag()
      val exactMatch = entries.firstOrNull { language -> language.languageTag.equals(exactTag, ignoreCase = true) }
      if (exactMatch != null) return exactMatch

      return entries.firstOrNull { language ->
        val supportedLocale = Locale.forLanguageTag(language.languageTag)
        LocaleListCompat.matchesLanguageAndScript(locale, supportedLocale)
      }
    }
  }
}

internal fun appLanguageFromLocales(locales: LocaleListCompat): AppLanguage =
  if (locales.isEmpty) {
    AppLanguage.English
  } else {
    (0 until locales.size()).firstNotNullOfOrNull { index -> locales[index]?.let(AppLanguage::fromLocale) }
      ?: AppLanguage.English
  }

internal fun currentAppLanguage(): AppLanguage =
  appLanguageFromLocales(
    NativeStringResources.explicitApplicationLocales().takeUnless(LocaleListCompat::isEmpty)
      ?: AppCompatDelegate.getApplicationLocales(),
  )

internal fun localesForAppLanguage(language: AppLanguage): LocaleListCompat = LocaleListCompat.forLanguageTags(language.languageTag)

internal fun setAppLanguage(language: AppLanguage) {
  val locales = localesForAppLanguage(language)
  NativeStringResources.setApplicationLocales(locales)
  // Keep AppCompat's pending locale in sync even during Application.onCreate,
  // before an Activity delegate exists. The platform write makes the default
  // migration durable on Android 13+; AppCompat owns Activity recreation.
  AppCompatDelegate.setApplicationLocales(locales)
  NativeStringResources.applyPlatformApplicationLocales(locales)
  notifyNativeLocaleChanged()
}

/** Migrates an unset/follow-system installation to the product default without replacing a saved choice. */
internal fun ensureDefaultAppLanguage(context: Context) {
  NativeStringResources.install(context)
  if (!NativeStringResources.hasExplicitApplicationLocale()) setAppLanguage(AppLanguage.English)
}

internal fun appLanguageRowSubtitle(language: AppLanguage): String = nativeString("ClawInOne interface · \$languageTag", language.languageTag)
