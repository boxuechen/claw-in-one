package ai.clawinone.androiduse.fixture

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

internal fun Activity.screenColumn(): LinearLayout =
  LinearLayout(this).apply {
    val screenPadding = dp(24)
    orientation = LinearLayout.VERTICAL
    setPadding(screenPadding, screenPadding, screenPadding, screenPadding)
    setOnApplyWindowInsetsListener { view, windowInsets ->
      val systemBars = windowInsets.getInsets(WindowInsets.Type.systemBars())
      view.setPadding(
        screenPadding + systemBars.left,
        screenPadding + systemBars.top,
        screenPadding + systemBars.right,
        screenPadding + systemBars.bottom,
      )
      windowInsets
    }
    layoutParams =
      ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
  }

internal fun Activity.screenTitle(text: CharSequence): TextView =
  TextView(this).apply {
    this.text = text
    textSize = 28f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, 0, 0, dp(24))
  }

internal fun View.addTopMargin(dp: Int) {
  val marginParams =
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
    )
  marginParams.topMargin = context.dp(dp)
  layoutParams = marginParams
}

internal fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private fun android.content.Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
