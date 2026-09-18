package ai.openclaw.app.ui.onboardingreview

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal const val extraAndroidOnboardingReview = "openclaw.onboardingReview"
internal const val extraAndroidOnboardingReviewScene = "openclaw.onboardingReviewScene"

internal val androidOnboardingReviewAvailable = false

internal fun parseAndroidOnboardingReviewScene(intent: Intent?): String? = null

/** Release builds keep the call site type-safe but expose no review entry or implementation. */
@Composable
internal fun AndroidOnboardingReviewRoute(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  initialScene: String? = null,
) = Unit
