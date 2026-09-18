package ai.openclaw.app.ui.onboardingreview

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidOnboardingReviewModeTest {
  @Test
  fun normalLaunchDoesNotEnterReview() {
    assertNull(parseAndroidOnboardingReviewScene(Intent(Intent.ACTION_MAIN)))
  }

  @Test
  fun reviewLaunchDefaultsToChecking() {
    val scene =
      parseAndroidOnboardingReviewScene(
        Intent(Intent.ACTION_MAIN).putExtra(extraAndroidOnboardingReview, true),
      )

    assertEquals(OnboardingReviewScene.Checking.rawValue, scene)
  }

  @Test
  fun reviewLaunchCanTargetAProductionScreen() {
    val scene =
      parseAndroidOnboardingReviewScene(
        Intent(Intent.ACTION_MAIN)
          .putExtra(extraAndroidOnboardingReview, true)
          .putExtra(extraAndroidOnboardingReviewScene, "ai"),
      )

    assertEquals(OnboardingReviewScene.Ai.rawValue, scene)
  }

  @Test
  fun sceneOrderMatchesTheFirstSetupReviewJourney() {
    assertEquals(
      listOf(
        "checking",
        "unsupported",
        "unknown",
        "developer-options",
        "linux-environment",
        "local-service",
        "environment",
        "installation",
        "gateway",
        "ai",
        "ready",
      ),
      OnboardingReviewScene.entries.map(OnboardingReviewScene::rawValue),
    )
  }
}
