package ai.openclaw.app.ui.web

import ai.openclaw.app.R
import ai.openclaw.app.gateway.normalizeGatewayTlsFingerprintInput
import ai.openclaw.app.gateway.shouldBypassGatewayProxy
import ai.openclaw.app.terminal.GatewayControlPage
import ai.openclaw.app.ui.LocalResolvedAppearanceIsDark
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Authenticated, hardened WebView host for gateway-served Control UI pages. */
@SuppressLint("SetJavaScriptEnabled")
// Deprecated file-URL settings remain force-disabled as defense in depth.
@Suppress("DEPRECATION")
@Composable
internal fun ControlUiWebView(
  page: GatewayControlPage,
  url: String,
  modifier: Modifier = Modifier,
  bootstrapToken: String? = null,
  onBootstrapConsumed: (String) -> Unit = {},
  retention: ControlUiWebViewRetention? = null,
  viewFactory: (Context) -> WebView = { WebView(it) },
  onViewAvailable: (WebView) -> Unit = {},
) {
  val context = LocalContext.current
  val darkAppearance = LocalResolvedAppearanceIsDark.current
  var rendererGeneration by remember { mutableIntStateOf(0) }
  val retainedGeneration = retention?.rendererGeneration
  val currentOnViewAvailable by rememberUpdatedState(onViewAvailable)

  // A WebView reads prefers-color-scheme from the Context it was built with, so an appearance
  // flip has to rebuild it; keying on the resolved boolean keeps that to real dark/light changes.
  // The reload is safe because both Control UI surfaces reattach to server-side state: the shell
  // outlives the page, and the desktop session lingers on the Gateway long enough to re-observe.
  key(darkAppearance, rendererGeneration, retainedGeneration) {
    AndroidView(
      modifier = modifier,
      factory = {
        retention?.take(page, url, darkAppearance)?.let { return@AndroidView it }
        val initialUrl = controlUiInitialUrl(url = url, bootstrapToken = bootstrapToken)
        val webView = viewFactory(controlUiWebViewContext(context, darkAppearance))
        val webSettings = webView.settings
        webSettings.setAllowContentAccess(false)
        webSettings.setAllowFileAccess(false)
        webSettings.setAllowFileAccessFromFileURLs(false)
        webSettings.setAllowUniversalAccessFromFileURLs(false)
        webSettings.setSafeBrowsingEnabled(true)
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.setSupportZoom(false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
          WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, false)
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // The native gateway connection already established this route's trust.
        // Reuse only that exact accepted fingerprint; every other SSL error cancels.
        // The same client protects both terminal and dashboard pages.
        val webViewClient =
          ControlUiWebViewClient(
            page = page,
            bootstrapToken = bootstrapToken,
            onBootstrapConsumed = onBootstrapConsumed,
            onRendererGone = {
              rendererGeneration += 1
              retention?.rendererGone()
            },
          )
        webView.webViewClient = webViewClient
        installControlUiAuthScript(webView, page)
        webViewClient.proxyLease =
          ControlUiWebViewProxyOverride.prepare(
            url = initialUrl,
            executor = context.mainExecutor,
            onReady = {
              webView.doOnLayout {
                if (!webViewClient.isReleased) webView.loadUrl(initialUrl)
              }
            },
          )
        retention?.hold(page, url, darkAppearance, webView)
        webView
      },
      update = { currentOnViewAvailable(it) },
      onRelease = { webView ->
        if (retention?.owns(webView) == true) {
          webView.clearFocus()
        } else {
          (webView.webViewClient as? ControlUiWebViewClient)?.release(webView)
        }
      },
    )
  }
}

/** Activity-shell-owned view lifetime. Trust/credentials/theme changes always retire the old page. */
internal class ControlUiWebViewRetention {
  var rendererGeneration by mutableIntStateOf(0)
    private set

  fun rendererGone() {
    // A destroyed real WebView may no longer return its former client. Drop the
    // reference explicitly instead of relying on the released-client check in take().
    view = null
    identity = null
    rendererGeneration += 1
  }

  private data class Identity(
    val page: GatewayControlPage,
    val url: String,
    val dark: Boolean,
  )

  private var identity: Identity? = null
  private var view: WebView? = null

  fun take(
    page: GatewayControlPage,
    url: String,
    dark: Boolean,
  ): WebView? {
    val existing = view ?: return null
    if (identity != Identity(page, url, dark) || (existing.webViewClient as? ControlUiWebViewClient)?.isReleased == true) {
      release()
      return null
    }
    (existing.parent as? ViewGroup)?.removeView(existing)
    return existing
  }

  fun hold(
    page: GatewayControlPage,
    url: String,
    dark: Boolean,
    webView: WebView,
  ) {
    identity = Identity(page, url, dark)
    view = webView
  }

  fun owns(webView: WebView): Boolean = view === webView

  fun hasPage(page: GatewayControlPage): Boolean = identity?.page == page && view?.let { (it.webViewClient as? ControlUiWebViewClient)?.isReleased == false } == true

  fun release() {
    view?.let { webView ->
      (webView.parent as? ViewGroup)?.removeView(webView)
      (webView.webViewClient as? ControlUiWebViewClient)?.release(webView)
    }
    view = null
    identity = null
  }
}

private fun controlUiWebViewContext(
  context: Context,
  darkAppearance: Boolean,
): Context {
  val configuration = Configuration(context.resources.configuration)
  val nightMode = if (darkAppearance) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
  configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
  // WebView derives prefers-color-scheme from the host theme's isLightTheme value.
  // Reapplying the app's DayNight theme to this resolved configuration keeps that value authoritative.
  return ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_OpenClawNode)
}

/**
 * Hands gateway credentials through the origin-restricted native startup contract,
 * keeping them out of page URLs and WebView history.
 */
internal fun installControlUiAuthScript(
  webView: WebView,
  page: GatewayControlPage,
) {
  if (page.token == null && page.password == null) return
  if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
  // Document-start rules are origins (scheme://host[:port]); a base-path URL
  // is an invalid rule and throws while constructing the WebView.
  val originRule = controlUiOriginRule(page.baseUrl) ?: return
  val gatewayUrl = page.baseUrl.replaceFirst("http", "ws")
  val payload =
    buildJsonObject {
      put("gatewayUrl", gatewayUrl)
      page.token?.let { put("token", it) }
      page.password?.let { put("password", it) }
    }
  val script =
    """
    (() => {
      try {
        Object.defineProperty(window, "__OPENCLAW_NATIVE_CONTROL_AUTH__", {
          value: $payload,
          configurable: true,
        });
      } catch (e) {}
    })();
    """.trimIndent()
  WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(originRule))
}

/** scheme://host[:port] origin for WebView script rules; brackets IPv6 hosts. */
internal fun controlUiOriginRule(baseUrl: String): String? {
  val uri = baseUrl.toUri()
  val scheme = uri.scheme ?: return null
  val host = uri.host ?: return null
  val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
  val port = if (uri.port != -1) ":${uri.port}" else ""
  return "$scheme://$hostPart$port"
}

private const val X509_CERTIFICATE_BUNDLE_KEY = "x509-certificate"

/**
 * Android WebView can resolve `100vh` to zero when navigation begins before its Compose host is
 * measured. Loading after layout prevents that path; this bridge repairs affected renderer/page
 * combinations and keeps the explicit height synchronized without changing healthy pages.
 */
internal val CONTROL_UI_VIEWPORT_BRIDGE_SCRIPT =
  """
  (() => {
    const bridgeKey = "__CLAW_IN_ONE_VIEWPORT_BRIDGE__";
    const sync = () => {
      const shell = document.querySelector(".shell");
      if (!(shell instanceof HTMLElement)) return;
      const viewportHeight = Math.round(Math.max(
        window.innerHeight || 0,
        window.visualViewport?.height || 0,
      ));
      if (viewportHeight <= 0) return;
      const bridgeOwnsHeight = shell.dataset.clawInOneViewport === "true";
      const viewportTargets = [document.documentElement, document.body, shell];
      const hasCollapsedTarget = viewportTargets.some(
        (target) => target.getBoundingClientRect().height <= 1,
      );
      if (!hasCollapsedTarget && !bridgeOwnsHeight) return;
      const value = `${'$'}{viewportHeight}px`;
      viewportTargets.forEach((target) => {
        if (target.style.height !== value) {
          target.style.setProperty("height", value, "important");
        }
      });
      shell.dataset.clawInOneViewport = "true";
    };
    if (!window[bridgeKey]) {
      window[bridgeKey] = true;
      window.addEventListener("resize", sync, { passive: true });
      window.visualViewport?.addEventListener("resize", sync, { passive: true });
      new MutationObserver(sync).observe(document.documentElement, {
        childList: true,
        subtree: true,
      });
    }
    sync();
  })();
  """.trimIndent()

internal enum class ControlUiProxyMode {
  System,
  Direct,
}

internal fun controlUiProxyMode(url: String): ControlUiProxyMode = if (shouldBypassGatewayProxy(url.toUri().host)) ControlUiProxyMode.Direct else ControlUiProxyMode.System

internal fun controlUiInitialUrl(
  url: String,
  bootstrapToken: String?,
): String {
  val token = bootstrapToken?.trim()?.takeIf { it.isNotEmpty() } ?: return url
  return url
    .toUri()
    .buildUpon()
    .encodedFragment("bootstrapToken=$token&bootstrapProfile=owner")
    .build()
    .toString()
}

private fun interface ControlUiProxyLease {
  fun release()
}

/**
 * WebView has a process-wide proxy override rather than a per-view proxy API. A generation lease
 * prevents an outgoing screen from clearing the override selected by the incoming screen.
 */
private object ControlUiWebViewProxyOverride {
  private val lock = Any()
  private var generation = 0L

  @SuppressLint("RequiresFeature")
  fun prepare(
    url: String,
    executor: java.util.concurrent.Executor,
    onReady: () -> Unit,
  ): ControlUiProxyLease {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
      onReady()
      return ControlUiProxyLease {}
    }

    // The explicit feature gate above is authoritative; lint cannot retain it across
    // this process-wide lease abstraction.
    val leaseGeneration = synchronized(lock) { ++generation }
    val controller = ProxyController.getInstance()
    val listener =
      Runnable {
        val isCurrent = synchronized(lock) { generation == leaseGeneration }
        if (isCurrent) onReady()
      }

    when (controlUiProxyMode(url)) {
      ControlUiProxyMode.Direct ->
        controller.setProxyOverride(
          ProxyConfig.Builder().addDirect().build(),
          executor,
          listener,
        )
      ControlUiProxyMode.System -> controller.clearProxyOverride(executor, listener)
    }

    return ControlUiProxyLease {
      val shouldClear =
        synchronized(lock) {
          if (generation != leaseGeneration) {
            false
          } else {
            generation += 1
            true
          }
        }
      if (shouldClear) controller.clearProxyOverride(executor) {}
    }
  }
}

// WebKit 1.17's lint detector reports Kotlin WebViewClient constructors even when this callback exists.
@SuppressLint("MissingOnRenderProcessGone")
private class ControlUiWebViewClient(
  private val page: GatewayControlPage,
  private val bootstrapToken: String?,
  private val onBootstrapConsumed: (String) -> Unit,
  private val onRendererGone: () -> Unit,
) : WebViewClient() {
  private var released = false
  private var bootstrapConsumed = false
  var proxyLease: ControlUiProxyLease? = null

  val isReleased: Boolean
    get() = released

  fun release(view: WebView) {
    if (released) return
    released = true
    proxyLease?.release()
    proxyLease = null
    view.stopLoading()
    view.destroy()
  }

  override fun onRenderProcessGone(
    view: WebView,
    detail: RenderProcessGoneDetail,
  ): Boolean {
    if (released) return true
    released = true
    proxyLease?.release()
    proxyLease = null
    // The renderer cannot be reused. Detach and destroy this instance before
    // advancing the Compose key so the authenticated page gets a fresh process.
    (view.parent as? ViewGroup)?.removeView(view)
    view.destroy()
    onRendererGone()
    return true
  }

  override fun onPageFinished(
    view: WebView,
    url: String,
  ) {
    super.onPageFinished(view, url)
    if (!sameHttpsOrigin(page.baseUrl, url)) return
    view.evaluateJavascript(CONTROL_UI_VIEWPORT_BRIDGE_SCRIPT, null)
    val token = bootstrapToken ?: return
    if (!bootstrapConsumed) {
      bootstrapConsumed = true
      onBootstrapConsumed(token)
    }
  }

  // Android lint cannot infer the exact pin and origin checks below; every other path cancels.
  // WebView exposes no pre-document certificate hook for successful CA-trusted handshakes;
  // this callback extends native pin trust only to recoverable self-signed errors.
  @SuppressLint("WebViewClientOnReceivedSslError")
  override fun onReceivedSslError(
    view: WebView,
    handler: android.webkit.SslErrorHandler,
    error: android.net.http.SslError,
  ) {
    // SslCertificate exposes the encoded leaf only through its AOSP saveState bundle.
    val encodedCertificate =
      android.net.http.SslCertificate
        .saveState(error.certificate)
        ?.getByteArray(X509_CERTIFICATE_BUNDLE_KEY)
    if (
      shouldProceedForPinnedControlUiSslError(
        pageBaseUrl = page.baseUrl,
        expectedFingerprint = page.tlsFingerprintSha256,
        errorUrl = error.url,
        encodedCertificate = encodedCertificate,
      )
    ) {
      // The native gateway connection already accepted this exact certificate.
      // Never extend the exception to another origin or a different certificate.
      handler.proceed()
    } else {
      handler.cancel()
    }
  }
}

internal fun shouldProceedForPinnedControlUiSslError(
  pageBaseUrl: String,
  expectedFingerprint: String?,
  errorUrl: String?,
  encodedCertificate: ByteArray?,
): Boolean {
  val expected =
    expectedFingerprint
      ?.let(::normalizeGatewayTlsFingerprintInput)
      ?: return false
  val certificate = encodedCertificate ?: return false
  if (!sameHttpsOrigin(pageBaseUrl, errorUrl)) return false
  return java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(certificate)
    .joinToString(separator = "") { byte ->
      "%02x".format(java.util.Locale.US, byte.toInt() and 0xff)
    } == expected
}

private fun sameHttpsOrigin(
  pageBaseUrl: String,
  errorUrl: String?,
): Boolean {
  val pageOrigin = parsedHttpsOrigin(pageBaseUrl) ?: return false
  val errorOrigin = errorUrl?.let(::parsedHttpsOrigin) ?: return false
  return pageOrigin == errorOrigin
}

private data class HttpsOrigin(
  val host: String,
  val port: Int,
)

private fun parsedHttpsOrigin(rawUrl: String): HttpsOrigin? {
  val uri = rawUrl.toUri()
  if (!uri.scheme.equals("https", ignoreCase = true)) return null
  val host = uri.host?.lowercase(java.util.Locale.US) ?: return null
  val port = uri.port.takeIf { it >= 0 } ?: 443
  return HttpsOrigin(host = host, port = port)
}
