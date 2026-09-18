package ai.openclaw.app.bootstrap

/** Release-owned inventory for the required OpenClaw execution environment. */
internal data class ExecutionEnvironmentReleaseManifest(
  val debianSeries: String,
  val packageSetGeneration: Int,
  val generalNodeVersion: String,
  val generalNodeNpmVersion: String,
  val generalNodeUrl: String,
  val generalNodeSizeBytes: Long,
  val generalNodeSha256: String,
  val scrcpyServerVersion: String,
  val scrcpyServerUrl: String,
  val scrcpyServerSizeBytes: Long,
  val scrcpyServerSha256: String,
)

internal val PINNED_EXECUTION_ENVIRONMENT =
  ExecutionEnvironmentReleaseManifest(
    debianSeries = "13",
    packageSetGeneration = 1,
    generalNodeVersion = "22.22.0",
    generalNodeNpmVersion = "10.9.4",
    generalNodeUrl = "https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-arm64.tar.xz",
    generalNodeSizeBytes = 29_977_428,
    generalNodeSha256 = "1bf1eb9ee63ffc4e5d324c0b9b62cf4a289f44332dfef9607cea1a0d9596ba6f",
    scrcpyServerVersion = "4.1",
    scrcpyServerUrl = "https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1",
    scrcpyServerSizeBytes = 733_706,
    scrcpyServerSha256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae",
  )
