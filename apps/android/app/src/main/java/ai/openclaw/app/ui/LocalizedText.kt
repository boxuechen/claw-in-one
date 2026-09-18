package ai.openclaw.app.ui

import ai.openclaw.app.firstGraphemeOrNull
import java.util.Locale

internal fun localizedUppercase(
  value: String,
  languageTag: String?,
  fallbackLocale: Locale = Locale.getDefault(),
): String = value.uppercase(languageTag?.let(Locale::forLanguageTag) ?: fallbackLocale)

internal fun localizedInitial(
  value: String,
  languageTag: String?,
  fallbackLocale: Locale = Locale.getDefault(),
): String? = value.firstGraphemeOrNull()?.let { localizedUppercase(it, languageTag, fallbackLocale) }
