package ai.openclaw.app

import android.content.Intent

/** Release builds have no screenshot-fixture implementation or launch surface. */
internal fun parseAndroidScreenshotLaunchIntent(intent: Intent?): AndroidScreenshotLaunch? = null
