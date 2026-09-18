package ai.openclaw.app.ui.terminal.web

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.terminal.GatewayControlPage
import ai.openclaw.app.terminal.TerminalControlAccess
import ai.openclaw.app.ui.terminal.terminalUrl
import ai.openclaw.app.ui.web.controlUiInitialUrl
import ai.openclaw.app.ui.web.controlUiOriginRule
import ai.openclaw.app.ui.web.installControlUiAuthScript
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

internal enum class TerminalBrowserPhase { Preparing, Connecting, Unavailable, Live, Exited, Replaced, AuthorizationRequired }

/**
 * Mobile input/presentation only. Authentication, trust, and WebView lifetime remain in the host.
 * Constructed exclusively by the Compose factory with shell-owned retention, never inflated from XML.
 */
@SuppressLint("ViewConstructor")
internal class MobileTerminalWebView(
  context: Context,
  private val retention: TerminalViewRetention,
  page: GatewayControlPage,
) : WebView(context) {
  private var restorationHandler: ScriptHandler? = null
  private var destroyed = false
  private val phaseValue = MutableStateFlow(TerminalBrowserPhase.Preparing)
  val phase = phaseValue.asStateFlow()
  private val main = Handler(Looper.getMainLooper())
  private val adapter =
    context.assets
      .open("terminal/mobile.js")
      .bufferedReader()
      .use { it.readText() }
  private var selecting = false
  private var scrollRemainder = 0f
  private val poll =
    object : Runnable {
      override fun run() {
        if (destroyed || !isAttachedToWindow || windowVisibility != View.VISIBLE) return
        evaluateJavascript("window.__clawPreviousSession=${JSONObject.quote(retention.lastSessionId)};" + adapter) { raw ->
          val snapshot = runCatching { JSONObject(raw) }.getOrNull()
          val value = snapshot?.optString("phase").orEmpty()
          snapshot?.optString("sessionId")?.takeIf { it.isNotEmpty() && it != "null" }?.let { retention.lastSessionId = it }
          phaseValue.value = TerminalBrowserPhase.entries.firstOrNull { it.name.equals(value, true) } ?: TerminalBrowserPhase.Preparing
        }
        main.postDelayed(this, 500)
      }
    }
  private val gestures =
    GestureDetector(
      context,
      object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
          selecting = false
          call("clearSelection")
          showKeyboard()
          performClick()
          return true
        }

        override fun onLongPress(e: MotionEvent) {
          selecting = true
          call("select", "${e.y / height.coerceAtLeast(1)},false")
        }

        override fun onScroll(
          e1: MotionEvent?,
          e2: MotionEvent,
          distanceX: Float,
          distanceY: Float,
        ): Boolean {
          if (selecting) {
            call("select", "${e2.y / height.coerceAtLeast(1)},true")
          } else {
            scrollRemainder += distanceY
            if (abs(scrollRemainder) >= 16 * resources.displayMetrics.density) {
              call("scroll", "${scrollRemainder / height.coerceAtLeast(1)}")
              scrollRemainder = 0f
            }
          }
          return true
        }
      },
    )

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    contentDescription = nativeString("Terminal input")
    installRestoration(page)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    main.removeCallbacks(poll)
    main.post(poll)
  }

  override fun onDetachedFromWindow() {
    main.removeCallbacks(poll)
    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    super.onDetachedFromWindow()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    // Framework construction can dispatch this callback before Kotlin fields are initialized.
    if (visibility == View.VISIBLE && isAttachedToWindow) {
      main.removeCallbacks(poll)
      main.post(poll)
    }
  }

  override fun destroy() {
    if (destroyed) return
    destroyed = true
    main.removeCallbacks(poll)
    restorationHandler?.remove()
    super.destroy()
  }

  override fun onSizeChanged(
    w: Int,
    h: Int,
    oldw: Int,
    oldh: Int,
  ) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0 && h > 0) post { call("fit") }
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  // The detector calls performClick from onSingleTapUp, rather than treating scroll/selection as clicks.
  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    return TerminalInputConnection(this, ::sendInput, ::handleTerminalKey)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleTerminalKey(event) || super.dispatchKeyEvent(event)

  private fun handleTerminalKey(event: KeyEvent): Boolean {
    val text = terminalKeyText(event) ?: return false
    if (event.action == KeyEvent.ACTION_DOWN)sendInput(text)
    return true
  }

  fun showKeyboard() {
    if (destroyed || phaseValue.value != TerminalBrowserPhase.Live) return
    requestFocus()
    context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
  }

  fun renewAccess(access: TerminalControlAccess) {
    check(controlUiOriginRule(url.orEmpty()) == controlUiOriginRule(access.page.baseUrl))
    installControlUiAuthScript(this, access.page)
    installRestoration(access.page)
    phaseValue.value = TerminalBrowserPhase.Preparing
    val destination = controlUiInitialUrl(terminalUrl(access.page.baseUrl), access.bootstrapToken)
    // Reload the same WebView so sessionStorage survives reauthorization.
    evaluateJavascript("location.replace(${JSONObject.quote(destination)});location.reload();", null)
  }

  @SuppressLint("RequiresFeature")
  private fun installRestoration(page: GatewayControlPage) {
    restorationHandler?.remove()
    restorationHandler = null
    val origin = controlUiOriginRule(page.baseUrl) ?: return
    val script = retention.restorationScript().takeIf { it.isNotEmpty() } ?: return
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      restorationHandler = WebViewCompat.addDocumentStartJavaScript(this, script, setOf(origin))
    }
  }

  fun sendInput(text: String) {
    if (phaseValue.value == TerminalBrowserPhase.Live)call("input", JSONObject.quote(text))
  }

  fun copySelection() {
    call("copy") { raw ->
      val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
      if (text.isEmpty()) {
        Toast.makeText(context, nativeString("Long press a line to select it."), Toast.LENGTH_SHORT).show()
      } else {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
      }
    }
  }

  fun paste(text: String) {
    if (phaseValue.value == TerminalBrowserPhase.Live)call("paste", JSONObject.quote(text))
  }

  fun acknowledgeReplacement() {
    call("acknowledge")
  }

  fun newSession() {
    call("newSession")
  }

  private fun call(
    method: String,
    args: String = "",
    result: ((String) -> Unit)? = null,
  ) {
    if (destroyed) return
    evaluateJavascript("window.clawTerminal?.$method($args)", result)
  }
}
