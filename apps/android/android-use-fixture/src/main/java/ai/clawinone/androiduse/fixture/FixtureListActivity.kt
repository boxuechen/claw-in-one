package ai.clawinone.androiduse.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class FixtureListActivity : Activity() {
  private var selectedProjectNumber: Int? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val taskName = intent.getStringExtra(EXTRA_TASK_NAME).orEmpty()
    val verificationEnabled = intent.getBooleanExtra(EXTRA_VERIFICATION_ENABLED, false)
    val selectionStatus =
      TextView(this).apply {
        id = R.id.fixture_selection_status
        text = getString(R.string.selection_empty)
        textSize = 18f
      }
    val content =
      screenColumn().apply {
        addView(screenTitle(getString(R.string.list_title)))
        addView(selectionStatus)
        repeat(PROJECT_COUNT) { index ->
          val projectNumber = index + 1
          addView(
            Button(this@FixtureListActivity).apply {
              if (projectNumber == ACCEPTANCE_PROJECT_NUMBER) {
                id = R.id.fixture_project_20
              }
              text = getString(R.string.project_format, projectNumber)
              addTopMargin(8)
              setOnClickListener {
                selectedProjectNumber = projectNumber
                selectionStatus.text = getString(R.string.selection_format, projectNumber)
              }
            },
          )
        }
        addView(
          Button(this@FixtureListActivity).apply {
            id = R.id.fixture_submit
            text = getString(R.string.submit)
            addTopMargin(20)
            setOnClickListener {
              val selected = selectedProjectNumber
              if (selected == null) {
                Toast.makeText(this@FixtureListActivity, R.string.selection_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
              }
              startActivity(
                Intent(this@FixtureListActivity, FixtureResultActivity::class.java)
                  .putExtra(EXTRA_TASK_NAME, taskName)
                  .putExtra(EXTRA_VERIFICATION_ENABLED, verificationEnabled)
                  .putExtra(EXTRA_PROJECT_NUMBER, selected),
              )
            }
          },
        )
      }

    setContentView(
      ScrollView(this).apply {
        id = R.id.fixture_project_list
        isFillViewport = true
        addView(content)
      },
    )
  }
}
