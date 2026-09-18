package ai.openclaw.app.bootstrap

import ai.openclaw.app.supervisor.SUPERVISOR_CONTROL_PROTOCOL_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class OpenClawReleaseTest {
  @Test
  fun productRuntimeAndBootstrapVersionsRemainIndependent() {
    assertEquals(4, BOOTSTRAP_HANDOFF_PROTOCOL_VERSION)
    assertEquals(10, SUPERVISOR_CONTROL_PROTOCOL_VERSION)
    assertEquals("2026.9.4", PINNED_OPENCLAW_RELEASE.version)
  }

  @Test
  fun releaseManifestPinsImmutableArm64Artifacts() {
    val release = PINNED_OPENCLAW_RELEASE

    assertEquals("2026.9.4", release.version)
    assertEquals("24.19.0", release.nodeVersion)
    assertEquals(
      "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.gz",
      release.nodeArchiveUrl,
    )
    assertTrue(release.nodeArchiveSha256.matches(Regex("[0-9a-f]{64}")))
    assertEquals(
      "https://registry.npmjs.org/openclaw/-/openclaw-2026.9.4.tgz",
      release.packageUrl,
    )
    assertTrue(release.packageIntegrity.startsWith("sha512-"))
    assertTrue(!release.nodeArchiveUrl.contains("latest"))
    assertTrue(!release.packageUrl.contains("latest"))
  }

  @Test
  fun embeddedNpmKeyAndSignatureMatchThePinnedPackage() {
    val release = PINNED_OPENCLAW_RELEASE
    val keyBytes = Base64.getDecoder().decode(release.registryPublicKey)
    assertEquals(
      "SHA256:DhQ8wR5APBvFHLF/+Tc+AYvPOdTpcIDqOhxsBHRwC7U",
      release.registryKeyId,
    )

    val publicKey =
      KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(
      "openclaw@${release.version}:${release.packageIntegrity}"
        .toByteArray(StandardCharsets.UTF_8),
    )
    assertTrue(verifier.verify(Base64.getDecoder().decode(release.packageSignature)))
  }

  @Test
  fun androidCapabilityPackPinsItsGoogleArtifactAndSdkTargets() {
    val release = PINNED_CAPABILITY_PACKS

    assertEquals("16111833", release.androidCommandToolsVersion)
    assertEquals(181_052_239, release.androidCommandToolsSizeBytes)
    assertTrue(release.androidCommandToolsUrl.startsWith("https://dl.google.com/android/repository/"))
    assertTrue(release.androidCommandToolsSha256.matches(Regex("[0-9a-f]{64}")))
    assertEquals(3, release.androidSdkChannel)
    assertEquals("37.0", release.androidPlatform)
    assertEquals("37.0.0", release.androidBuildTools)
    assertEquals("29.0.14206865", release.androidNativeNdk)
    assertEquals("3.22.1", release.androidNativeCmake)
  }

  @Test
  fun requiredExecutionEnvironmentPinsArm64NodeAndScrcpyServer() {
    val release = PINNED_EXECUTION_ENVIRONMENT

    assertEquals("13", release.debianSeries)
    assertEquals(1, release.packageSetGeneration)
    assertEquals("22.22.0", release.generalNodeVersion)
    assertTrue(release.generalNodeUrl.endsWith("node-v22.22.0-linux-arm64.tar.xz"))
    assertTrue(release.generalNodeSha256.matches(Regex("[0-9a-f]{64}")))
    assertEquals("4.1", release.scrcpyServerVersion)
    assertTrue(release.scrcpyServerUrl.endsWith("/v4.1/scrcpy-server-v4.1"))
    assertEquals(733_706, release.scrcpyServerSizeBytes)
    assertTrue(release.scrcpyServerSha256.matches(Regex("[0-9a-f]{64}")))
  }

  @Test
  fun flutterCapabilityPackPinsItsArm64ToolchainAndAndroidTargets() {
    val release = PINNED_CAPABILITY_PACKS

    assertEquals("3.47.4", release.flutterVersion)
    assertEquals("3.13.3", release.flutterDartVersion)
    assertTrue(release.flutterFrameworkRevision.matches(Regex("[0-9a-f]{40}")))
    assertTrue(release.flutterEngineRevision.matches(Regex("[0-9a-f]{40}")))
    assertTrue(release.flutterUrl.startsWith("https://storage.googleapis.com/flutter_infra_release/releases/"))
    assertTrue(release.flutterSha256.matches(Regex("[0-9a-f]{64}")))
    assertEquals("36", release.flutterAndroidPlatform)
    assertEquals("28.2.13676358", release.flutterAndroidNdk)
  }
}
