package ai.openclaw.app.bootstrap

internal data class OpenClawReleaseManifest(
  val version: String,
  val nodeVersion: String,
  val nodeArchiveUrl: String,
  val nodeArchiveSizeBytes: Long,
  val nodeArchiveSha256: String,
  val packageUrl: String,
  val packageSizeBytes: Long,
  val packageIntegrity: String,
  val packageSignature: String,
  val registryKeyId: String,
  val registryPublicKey: String,
)

internal val PINNED_OPENCLAW_RELEASE =
  OpenClawReleaseManifest(
    version = "2026.9.4",
    nodeVersion = "24.19.0",
    nodeArchiveUrl = "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.gz",
    nodeArchiveSizeBytes = 57_128_466,
    nodeArchiveSha256 = "d28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f",
    packageUrl = "https://registry.npmjs.org/openclaw/-/openclaw-2026.9.4.tgz",
    packageSizeBytes = 67_342_399,
    packageIntegrity =
      "sha512-lTQpEEe1Xm3u2PCHaPEr+vP8paGk1vLdHuzdItsNToaLI6hAqRVvgJYg+GxukJhETJp4tPy/S1Gftl4KuB8n7A==",
    packageSignature =
      "MEYCIQDxA/0bwzB28hpKSEaX9+wwpHLHL4/FpYa/SWjurNp8AgIhAIuKqGP1lsQnpS6BAHxldFcqa86DCfvHbakVqd9D4ASH",
    registryKeyId = "SHA256:DhQ8wR5APBvFHLF/+Tc+AYvPOdTpcIDqOhxsBHRwC7U",
    registryPublicKey =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEY6Ya7W++7aUPzvMTrezH6Ycx3c+HOKYCcNGybJZSCJq/fd7Qa8uuAKtdIkUQtQiEKERhAmE5lMMJhP8OkDOa2g==",
  )
