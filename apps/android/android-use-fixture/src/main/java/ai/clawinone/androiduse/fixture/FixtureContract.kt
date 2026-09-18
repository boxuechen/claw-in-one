package ai.clawinone.androiduse.fixture

internal const val EXTRA_TASK_NAME = "task_name"
internal const val EXTRA_VERIFICATION_ENABLED = "verification_enabled"
internal const val EXTRA_PROJECT_NUMBER = "project_number"
internal const val PROJECT_COUNT = 24
internal const val ACCEPTANCE_PROJECT_NUMBER = 20
internal const val RESULT_DELAY_MS = 750L

// A short language-independent golden path; the original form/list task remains available.
internal fun smokeCountLabel(count: Int): String = "Smoke count: $count"

internal data class FixtureResult(
  val taskName: String,
  val verificationEnabled: Boolean,
  val projectNumber: Int,
)

internal fun FixtureResult.render(
  verificationOn: String,
  verificationOff: String,
): String = "结果：任务名称=$taskName；验证=${if (verificationEnabled) verificationOn else verificationOff}；项目=$projectNumber"
