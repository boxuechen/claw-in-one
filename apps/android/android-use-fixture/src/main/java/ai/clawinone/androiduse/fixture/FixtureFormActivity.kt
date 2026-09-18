package ai.clawinone.androiduse.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

class FixtureFormActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val taskName =
      EditText(this).apply {
        id = R.id.fixture_task_name
        hint = getString(R.string.task_name_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
      }
    val verification =
      CheckBox(this).apply {
        id = R.id.fixture_verification_toggle
        text = getString(R.string.verification_toggle)
        addTopMargin(20)
      }
    val next =
      Button(this).apply {
        id = R.id.fixture_next
        text = getString(R.string.next)
        addTopMargin(20)
        setOnClickListener {
          val enteredTaskName = taskName.text.toString().trim()
          if (enteredTaskName.isEmpty()) {
            taskName.error = getString(R.string.task_name_required)
            return@setOnClickListener
          }
          startActivity(
            Intent(this@FixtureFormActivity, FixtureListActivity::class.java)
              .putExtra(EXTRA_TASK_NAME, enteredTaskName)
              .putExtra(EXTRA_VERIFICATION_ENABLED, verification.isChecked),
          )
        }
      }

    setContentView(
      screenColumn().apply {
        addView(screenTitle(getString(R.string.form_title)))
        addView(
          TextView(this@FixtureFormActivity).apply {
            text = getString(R.string.fixture_build_version)
          },
        )
        addView(
          Button(this@FixtureFormActivity).apply {
            id = R.id.fixture_smoke_increment
            var count = 0
            text = smokeCountLabel(count)
            setOnClickListener {
              count += 1
              text = smokeCountLabel(count)
            }
          },
        )
        addView(
          TextView(this@FixtureFormActivity).apply {
            text = getString(R.string.task_name_label)
            textSize = 18f
          },
        )
        addView(taskName)
        addView(verification)
        addView(next)
      },
    )
  }
}
