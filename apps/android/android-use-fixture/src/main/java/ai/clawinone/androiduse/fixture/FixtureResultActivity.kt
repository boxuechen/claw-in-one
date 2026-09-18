package ai.clawinone.androiduse.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class FixtureResultActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val result =
      FixtureResult(
        taskName = intent.getStringExtra(EXTRA_TASK_NAME).orEmpty(),
        verificationEnabled = intent.getBooleanExtra(EXTRA_VERIFICATION_ENABLED, false),
        projectNumber = intent.getIntExtra(EXTRA_PROJECT_NUMBER, 0),
      )
    val resultText =
      TextView(this).apply {
        id = R.id.fixture_result
        text = getString(R.string.result_pending)
        textSize = 20f
      }

    setContentView(
      screenColumn().apply {
        addView(screenTitle(getString(R.string.result_title)))
        addView(resultText)
      },
    )

    resultText.postDelayed(
      {
        resultText.text =
          result.render(
            verificationOn = getString(R.string.verification_on),
            verificationOff = getString(R.string.verification_off),
          )
      },
      RESULT_DELAY_MS,
    )
  }
}
