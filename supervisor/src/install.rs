use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use fs2::FileExt;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use sha2::{Digest, Sha256, Sha512};
use std::fs::{self, File, OpenOptions};
use std::io::Read;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use crate::config::{Config, InstallManifest};
use crate::protocol::Stage;

const INTERNAL_PLUGIN_OVERLAYS: [(&str, &str); 4] = [
    ("android-use", "android-use"),
    ("vscreen-foundation", "vscreen-foundation"),
    ("android-developer-bridge", "android-developer-bridge"),
    ("web-development", "web-development"),
];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DownloadProgress {
    pub completed_bytes: u64,
    pub total_bytes: u64,
}

#[derive(Debug)]
pub struct InstallError {
    pub code: u8,
    pub message: String,
}

pub fn install(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if std::env::consts::ARCH != "aarch64" {
        return Err(failure(24, "the pinned runtime supports ARM64 only"));
    }
    let root = &config.install_root;
    let cache = root.join("cache");
    let runtimes = root.join("runtimes");
    let releases = root.join("releases");
    for directory in [root, &cache, &runtimes, &releases] {
        fs::create_dir_all(directory).map_err(|error| {
            failure(21, format!("cannot create installation directory: {error}"))
        })?;
    }
    let lock_path = root.join("install.lock");
    let lock = OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .truncate(false)
        .open(&lock_path)
        .map_err(|error| failure(26, format!("cannot open installation lock: {error}")))?;
    acquire_lock(&lock)?;

    let result = install_locked(config, &cache, &runtimes, &releases, &mut report);
    let _ = lock.unlock();
    result
}

fn install_locked(
    config: &Config,
    cache: &Path,
    runtimes: &Path,
    releases: &Path,
    report: &mut impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    let manifest = &config.manifest;
    let runtime_dir = runtimes.join(format!("node-v{}-linux-arm64", manifest.node_version));
    let node_archive = cache.join(format!(
        "node-v{}-linux-arm64.tar.gz",
        manifest.node_version
    ));
    if !verify_sha256(&node_archive, &manifest.node_sha256) {
        download(
            &manifest.node_url,
            &node_archive,
            manifest.node_size_bytes,
            Stage::DownloadingNode,
            report,
        )?;
    }
    report_stage(report, Stage::VerifyingNode)?;
    if !verify_sha256(&node_archive, &manifest.node_sha256) {
        return Err(failure(22, "Node archive digest mismatch"));
    }
    if !verify_node(&runtime_dir, &manifest.node_version) {
        report_stage(report, Stage::InstallingNode)?;
        move_invalid_directory(&runtime_dir)?;
        let staging = suffixed_path(&runtime_dir, "staging");
        remove_directory_if_present(&staging)?;
        fs::create_dir_all(&staging)
            .map_err(|error| failure(21, format!("cannot create runtime staging: {error}")))?;
        run_checked(
            Command::new("tar")
                .arg("-xzf")
                .arg(&node_archive)
                .arg("-C")
                .arg(&staging)
                .arg("--strip-components=1"),
            23,
            "cannot extract Node runtime",
        )?;
        if !verify_node(&staging, &manifest.node_version) {
            return Err(failure(23, "staged Node runtime failed verification"));
        }
        fs::rename(&staging, &runtime_dir)
            .map_err(|error| failure(23, format!("cannot activate Node runtime: {error}")))?;
    }

    let node_bin = runtime_dir.join("bin/node");
    let npm_bin = runtime_dir.join("bin/npm");
    let package = cache.join(format!("openclaw-{}.tgz", manifest.openclaw_version));
    if !verify_openclaw_package(&package, manifest) {
        download(
            &manifest.openclaw_url,
            &package,
            manifest.openclaw_size_bytes,
            Stage::DownloadingOpenClaw,
            report,
        )?;
    }
    report_stage(report, Stage::VerifyingOpenClaw)?;
    verify_openclaw_package_result(&package, manifest)?;

    report_stage(report, Stage::InstallingOpenClaw)?;
    let overlay_fingerprint = internal_plugin_fingerprint(&config.shared_dir)?;
    let release_dir = releases.join(format!(
        "openclaw-{}-clawinone-{}",
        manifest.openclaw_version, overlay_fingerprint
    ));
    if !verify_clawinone_release(
        &release_dir,
        &runtime_dir,
        &manifest.openclaw_version,
        &config.shared_dir,
    ) {
        move_invalid_directory(&release_dir)?;
        let staging = suffixed_path(&release_dir, "staging");
        if !verify_clawinone_release(
            &staging,
            &runtime_dir,
            &manifest.openclaw_version,
            &config.shared_dir,
        ) {
            remove_directory_if_present(&staging)?;
            let mut command = Command::new(&npm_bin);
            command
                .arg("install")
                .arg("-g")
                .arg("--prefix")
                .arg(&staging)
                .arg(&package)
                .arg("--no-fund")
                .arg("--no-audit")
                .env("PATH", runtime_path(&runtime_dir));
            if npm_supports_allow_scripts(&npm_bin, &runtime_dir) {
                command.arg(
                    "--allow-scripts=openclaw,@google/genai,koffi,tree-sitter-bash,protobufjs",
                );
            }
            run_checked(&mut command, 25, "npm could not install OpenClaw")?;
            if !verify_openclaw(&staging, &runtime_dir, &manifest.openclaw_version) {
                return Err(failure(25, "staged OpenClaw failed verification"));
            }
            install_internal_plugins(&config.shared_dir, &staging)?;
            if !verify_clawinone_release(
                &staging,
                &runtime_dir,
                &manifest.openclaw_version,
                &config.shared_dir,
            ) {
                return Err(failure(
                    25,
                    "staged ClawInOne OpenClaw release failed verification",
                ));
            }
        }
        fs::rename(&staging, &release_dir)
            .map_err(|error| failure(25, format!("cannot activate OpenClaw release: {error}")))?;
    }

    report_stage(report, Stage::VerifyingOpenClawInstallation)?;
    if !verify_clawinone_release(
        &release_dir,
        &runtime_dir,
        &manifest.openclaw_version,
        &config.shared_dir,
    ) {
        return Err(failure(
            25,
            "active ClawInOne OpenClaw release failed verification",
        ));
    }
    activate_current(&config.install_root, &release_dir)?;
    write_runtime_environment(&config.install_root, &runtime_dir, manifest)?;
    let _ = node_bin;
    Ok(())
}

fn acquire_lock(lock: &File) -> Result<(), InstallError> {
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        match lock.try_lock_exclusive() {
            Ok(()) => return Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() >= deadline {
                    return Err(failure(27, "another installation is still running"));
                }
                thread::sleep(Duration::from_millis(250));
            }
            Err(error) => {
                return Err(failure(26, format!("cannot lock installation: {error}")));
            }
        }
    }
}

pub(crate) fn download(
    url: &str,
    destination: &Path,
    total_bytes: u64,
    stage: Stage,
    report: &mut impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    let temporary = suffixed_path(destination, "download");
    remove_file_if_present(&temporary)?;
    report_progress(report, stage, 0, total_bytes)?;
    let mut child = download_command(url, &temporary)
        .stdout(Stdio::null())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|error| failure(28, format!("cannot start artifact download: {error}")))?;
    let mut completed_bytes = 0;
    loop {
        let observed = fs::metadata(&temporary)
            .map(|value| value.len())
            .unwrap_or(0);
        if observed != completed_bytes {
            if observed > total_bytes {
                let _ = child.kill();
                return Err(failure(28, "artifact exceeded declared size"));
            }
            completed_bytes = observed;
            report_progress(report, stage, completed_bytes, total_bytes)?;
        }
        if let Some(status) = child
            .try_wait()
            .map_err(|error| failure(28, format!("cannot inspect artifact download: {error}")))?
        {
            if !status.success() {
                return Err(failure(28, "artifact download failed"));
            }
            break;
        }
        thread::sleep(Duration::from_millis(200));
    }
    completed_bytes = fs::metadata(&temporary)
        .map(|value| value.len())
        .map_err(|error| failure(28, format!("cannot inspect downloaded artifact: {error}")))?;
    report_progress(report, stage, completed_bytes, total_bytes)?;
    if completed_bytes != total_bytes {
        return Err(failure(28, "artifact download was incomplete"));
    }
    File::open(&temporary)
        .and_then(|file| file.sync_all())
        .map_err(|error| failure(28, format!("cannot sync artifact: {error}")))?;
    fs::rename(&temporary, destination)
        .map_err(|error| failure(28, format!("cannot publish artifact: {error}")))
}

fn download_command(url: &str, destination: &Path) -> Command {
    let mut command = Command::new("curl");
    command
        .arg("-fsSL")
        .arg("--proto")
        .arg("=https")
        .arg("--tlsv1.2")
        .arg("--connect-timeout")
        .arg("15")
        .arg("--speed-limit")
        .arg("1024")
        .arg("--speed-time")
        .arg("30")
        .arg("--retry")
        .arg("3")
        .arg("--retry-delay")
        .arg("1")
        .arg("--retry-all-errors")
        .arg("-o")
        .arg(destination)
        .arg(url);
    command
}

pub(crate) fn verify_sha256(path: &Path, expected: &str) -> bool {
    digest_file::<Sha256>(path).is_some_and(|digest| hex::encode(digest) == expected)
}

fn verify_openclaw_package(path: &Path, manifest: &InstallManifest) -> bool {
    verify_openclaw_package_result(path, manifest).is_ok()
}

fn verify_openclaw_package_result(
    path: &Path,
    manifest: &InstallManifest,
) -> Result<(), InstallError> {
    let expected_digest = manifest
        .openclaw_integrity
        .strip_prefix("sha512-")
        .and_then(|encoded| BASE64.decode(encoded).ok())
        .ok_or_else(|| failure(22, "invalid OpenClaw integrity metadata"))?;
    let actual_digest =
        digest_file::<Sha512>(path).ok_or_else(|| failure(22, "cannot read OpenClaw package"))?;
    if actual_digest.as_slice() != expected_digest {
        return Err(failure(22, "OpenClaw package integrity mismatch"));
    }
    let key_der = BASE64
        .decode(&manifest.npm_public_key)
        .map_err(|_| failure(22, "invalid registry public key"))?;
    let key = VerifyingKey::from_public_key_der(&key_der)
        .map_err(|_| failure(22, "invalid registry public key"))?;
    let signature_der = BASE64
        .decode(&manifest.openclaw_signature)
        .map_err(|_| failure(22, "invalid package signature"))?;
    let signature = Signature::from_der(&signature_der)
        .map_err(|_| failure(22, "invalid package signature"))?;
    let signed = format!(
        "openclaw@{}:{}",
        manifest.openclaw_version, manifest.openclaw_integrity
    );
    key.verify(signed.as_bytes(), &signature)
        .map_err(|_| failure(22, "OpenClaw package signature mismatch"))
}

fn digest_file<D: Digest + Default>(path: &Path) -> Option<Vec<u8>> {
    let mut file = File::open(path).ok()?;
    let mut digest = D::default();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = file.read(&mut buffer).ok()?;
        if count == 0 {
            break;
        }
        digest.update(&buffer[..count]);
    }
    Some(digest.finalize().to_vec())
}

fn verify_node(runtime: &Path, version: &str) -> bool {
    Command::new(runtime.join("bin/node"))
        .arg("--version")
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .is_some_and(|actual| actual.trim() == format!("v{version}"))
}

fn verify_openclaw(release: &Path, runtime: &Path, version: &str) -> bool {
    let expected = format!("OpenClaw {version}");
    Command::new(release.join("bin/openclaw"))
        .arg("--version")
        .env("PATH", runtime_path(runtime))
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .is_some_and(|actual| valid_version(actual.trim(), &expected))
}

pub(crate) fn verify_clawinone_release(
    release: &Path,
    runtime: &Path,
    version: &str,
    shared_dir: &Path,
) -> bool {
    verify_openclaw(release, runtime, version) && verify_internal_plugins(shared_dir, release)
}

fn bundled_extensions_dir(release: &Path) -> PathBuf {
    release.join("lib/node_modules/openclaw/dist/extensions")
}

fn internal_plugin_fingerprint(shared_dir: &Path) -> Result<String, InstallError> {
    let mut digest = Sha256::new();
    for (source_name, destination_name) in INTERNAL_PLUGIN_OVERLAYS {
        digest.update(source_name.as_bytes());
        digest.update([0]);
        digest.update(destination_name.as_bytes());
        digest.update([0]);
        digest.update(plugin_tree_digest(&shared_dir.join(source_name))?);
    }
    Ok(hex::encode(digest.finalize())[..16].to_owned())
}

fn install_internal_plugins(shared_dir: &Path, release: &Path) -> Result<(), InstallError> {
    let extensions = bundled_extensions_dir(release);
    let metadata = fs::symlink_metadata(&extensions).map_err(|error| {
        failure(
            25,
            format!("cannot inspect OpenClaw bundled extensions directory: {error}"),
        )
    })?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(failure(
            25,
            "OpenClaw bundled extensions directory is not a regular directory",
        ));
    }
    for (source_name, destination_name) in INTERNAL_PLUGIN_OVERLAYS {
        let source = shared_dir.join(source_name);
        let destination = extensions.join(destination_name);
        remove_directory_if_present(&destination)?;
        copy_plugin_tree(&source, &destination, &source)?;
    }
    Ok(())
}

fn verify_internal_plugins(shared_dir: &Path, release: &Path) -> bool {
    INTERNAL_PLUGIN_OVERLAYS
        .iter()
        .all(|(source_name, destination_name)| {
            let source = shared_dir.join(source_name);
            let destination = bundled_extensions_dir(release).join(destination_name);
            matches!(
                (plugin_tree_digest(&source), plugin_tree_digest(&destination)),
                (Ok(expected), Ok(actual)) if expected == actual
            )
        })
}

fn plugin_tree_digest(root: &Path) -> Result<Vec<u8>, InstallError> {
    let mut files = Vec::new();
    collect_plugin_files(root, root, &mut files)?;
    if files.is_empty() {
        return Err(failure(25, "bundled plugin directory is empty"));
    }
    files.sort();
    let mut digest = Sha256::new();
    for relative in files {
        let encoded = relative.to_string_lossy();
        digest.update((encoded.len() as u64).to_le_bytes());
        digest.update(encoded.as_bytes());
        let content = fs::read(root.join(&relative))
            .map_err(|error| failure(25, format!("cannot read bundled plugin file: {error}")))?;
        digest.update((content.len() as u64).to_le_bytes());
        digest.update(content);
    }
    Ok(digest.finalize().to_vec())
}

fn collect_plugin_files(
    root: &Path,
    directory: &Path,
    files: &mut Vec<PathBuf>,
) -> Result<(), InstallError> {
    let metadata = fs::symlink_metadata(directory).map_err(|error| {
        failure(
            25,
            format!("cannot inspect bundled plugin directory: {error}"),
        )
    })?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(failure(
            25,
            "bundled plugin path is not a regular directory",
        ));
    }
    let entries = fs::read_dir(directory)
        .map_err(|error| failure(25, format!("cannot read bundled plugin directory: {error}")))?;
    for entry in entries {
        let entry = entry
            .map_err(|error| failure(25, format!("cannot read bundled plugin entry: {error}")))?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path).map_err(|error| {
            failure(25, format!("cannot inspect bundled plugin entry: {error}"))
        })?;
        if metadata.file_type().is_symlink() {
            return Err(failure(25, "bundled plugin tree must not contain symlinks"));
        }
        if metadata.is_dir() {
            collect_plugin_files(root, &path, files)?;
        } else if metadata.is_file() {
            files.push(
                path.strip_prefix(root)
                    .map_err(|_| failure(25, "bundled plugin file escaped its source directory"))?
                    .to_owned(),
            );
        } else {
            return Err(failure(
                25,
                "bundled plugin tree contains a non-regular entry",
            ));
        }
    }
    Ok(())
}

fn copy_plugin_tree(source: &Path, destination: &Path, root: &Path) -> Result<(), InstallError> {
    let metadata = fs::symlink_metadata(source)
        .map_err(|error| failure(25, format!("cannot inspect bundled plugin source: {error}")))?;
    if metadata.file_type().is_symlink() {
        return Err(failure(25, "bundled plugin tree must not contain symlinks"));
    }
    if metadata.is_dir() {
        fs::create_dir(destination).map_err(|error| {
            failure(
                25,
                format!("cannot create bundled plugin directory: {error}"),
            )
        })?;
        for entry in fs::read_dir(source)
            .map_err(|error| failure(25, format!("cannot read bundled plugin source: {error}")))?
        {
            let entry = entry.map_err(|error| {
                failure(
                    25,
                    format!("cannot read bundled plugin source entry: {error}"),
                )
            })?;
            copy_plugin_tree(&entry.path(), &destination.join(entry.file_name()), root)?;
        }
        return Ok(());
    }
    if !metadata.is_file() || !source.starts_with(root) {
        return Err(failure(25, "bundled plugin source is not a regular file"));
    }
    fs::copy(source, destination)
        .map(|_| ())
        .map_err(|error| failure(25, format!("cannot copy bundled plugin file: {error}")))
}

fn npm_supports_allow_scripts(npm: &Path, runtime: &Path) -> bool {
    let Some(version) = Command::new(npm)
        .arg("--version")
        .env("PATH", runtime_path(runtime))
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
    else {
        return false;
    };
    let mut fields = version.trim().split('.');
    let major = fields.next().and_then(|value| value.parse::<u64>().ok());
    let minor = fields.next().and_then(|value| value.parse::<u64>().ok());
    matches!((major, minor), (Some(major), Some(minor)) if major > 11 || (major == 11 && minor >= 16))
}

fn activate_current(root: &Path, release: &Path) -> Result<(), InstallError> {
    let current = root.join("current");
    let next = root.join(format!("current.next.{}", std::process::id()));
    remove_file_if_present(&next)?;
    symlink(release, &next)
        .map_err(|error| failure(25, format!("cannot stage current release link: {error}")))?;
    fs::rename(&next, &current)
        .map_err(|error| failure(25, format!("cannot activate current release: {error}")))
}

fn write_runtime_environment(
    root: &Path,
    runtime: &Path,
    manifest: &InstallManifest,
) -> Result<(), InstallError> {
    let current_bin = root.join("current/bin");
    let content = format!(
        "export PATH={}:{}:\"$PATH\"\nexport OPENCLAW_SUPERVISOR_MODE=external\nexport CLAW_IN_ONE_OPENCLAW_VERSION={}\n",
        shell_quote(&runtime.join("bin").to_string_lossy()),
        shell_quote(&current_bin.to_string_lossy()),
        shell_quote(&manifest.openclaw_version),
    );
    let destination = root.join("runtime.env");
    let temporary = root.join(format!("runtime.env.next.{}", std::process::id()));
    fs::write(&temporary, content)
        .and_then(|()| fs::rename(&temporary, &destination))
        .map_err(|error| failure(25, format!("cannot write runtime environment: {error}")))
}

fn runtime_path(runtime: &Path) -> String {
    format!(
        "{}:/usr/local/bin:/usr/bin:/bin",
        runtime.join("bin").display()
    )
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}

fn move_invalid_directory(path: &Path) -> Result<(), InstallError> {
    if !path.exists() && fs::symlink_metadata(path).is_err() {
        return Ok(());
    }
    let invalid = PathBuf::from(format!(
        "{}.invalid.{}.{}",
        path.display(),
        std::process::id(),
        crate::protocol::now()
    ));
    fs::rename(path, invalid)
        .map_err(|error| failure(25, format!("cannot quarantine invalid release: {error}")))
}

fn suffixed_path(path: &Path, suffix: &str) -> PathBuf {
    PathBuf::from(format!("{}.{}", path.display(), suffix))
}

fn remove_file_if_present(path: &Path) -> Result<(), InstallError> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(failure(21, format!("cannot remove stale file: {error}"))),
    }
}

fn remove_directory_if_present(path: &Path) -> Result<(), InstallError> {
    match fs::remove_dir_all(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(failure(
            21,
            format!("cannot remove stale staging directory: {error}"),
        )),
    }
}

pub(crate) fn run_checked(
    command: &mut Command,
    code: u8,
    context: &str,
) -> Result<(), InstallError> {
    let status = command
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status()
        .map_err(|error| failure(code, format!("{context}: {error}")))?;
    if status.success() {
        Ok(())
    } else {
        Err(failure(
            status
                .code()
                .filter(|value| (1..=255).contains(value))
                .map_or(code, |value| value as u8),
            context,
        ))
    }
}

fn report_stage(
    report: &mut impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
    stage: Stage,
) -> Result<(), InstallError> {
    report(stage, None).map_err(|error| failure(29, error))
}

fn report_progress(
    report: &mut impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
    stage: Stage,
    completed_bytes: u64,
    total_bytes: u64,
) -> Result<(), InstallError> {
    report(
        stage,
        Some(DownloadProgress {
            completed_bytes,
            total_bytes,
        }),
    )
    .map_err(|error| failure(29, error))
}

fn valid_version(actual: &str, expected: &str) -> bool {
    if actual == expected {
        return true;
    }
    let Some(commit) = actual
        .strip_prefix(&format!("{expected} ("))
        .and_then(|value| value.strip_suffix(')'))
    else {
        return false;
    };
    (7..=40).contains(&commit.len())
        && commit
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn failure(code: u8, message: impl Into<String>) -> InstallError {
    InstallError {
        code,
        message: message.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        INTERNAL_PLUGIN_OVERLAYS, download_command, install_internal_plugins,
        internal_plugin_fingerprint, shell_quote, valid_version, verify_internal_plugins,
        verify_openclaw_package_result,
    };
    use crate::config::InstallManifest;
    use base64::Engine;
    use base64::engine::general_purpose::STANDARD as BASE64;
    use p256::ecdsa::signature::Signer;
    use p256::ecdsa::{Signature, SigningKey};
    use p256::pkcs8::EncodePublicKey;
    use sha2::{Digest, Sha512};
    use std::fs;
    use std::path::Path;

    #[test]
    fn shell_environment_values_are_literal() {
        assert_eq!(shell_quote("a b"), "'a b'");
        assert_eq!(shell_quote("a'b"), "'a'\"'\"'b'");
    }

    #[test]
    fn version_accepts_only_the_pin_or_its_commit_suffix() {
        assert!(valid_version("OpenClaw 1", "OpenClaw 1"));
        assert!(valid_version("OpenClaw 1 (abcdef1)", "OpenClaw 1"));
        assert!(!valid_version("OpenClaw 2", "OpenClaw 1"));
    }

    #[test]
    fn internal_plugins_are_copied_and_verified_as_one_release_overlay() {
        let root =
            std::env::temp_dir().join(format!("clawinone-plugin-overlay-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let shared = root.join("shared");
        let release = root.join("release");
        fs::create_dir_all(release.join("lib/node_modules/openclaw/dist/extensions")).unwrap();
        for (source_name, _) in INTERNAL_PLUGIN_OVERLAYS {
            let source = shared.join(source_name);
            fs::create_dir_all(source.join("nested")).unwrap();
            fs::write(
                source.join("package.json"),
                format!("{{\"name\":\"{source_name}\"}}"),
            )
            .unwrap();
            fs::write(source.join("nested/index.mjs"), "export default true;\n").unwrap();
        }

        let fingerprint = internal_plugin_fingerprint(&shared).unwrap();
        assert_eq!(fingerprint.len(), 16);
        install_internal_plugins(&shared, &release).unwrap();
        assert!(verify_internal_plugins(&shared, &release));
        assert!(
            release
                .join("lib/node_modules/openclaw/dist/extensions/vscreen-foundation")
                .is_dir()
        );

        fs::write(
            shared.join("vscreen-foundation/nested/index.mjs"),
            "export default false;\n",
        )
        .unwrap();
        assert_ne!(internal_plugin_fingerprint(&shared).unwrap(), fingerprint);
        assert!(!verify_internal_plugins(&shared, &release));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn download_fails_closed_when_a_connection_stalls() {
        let command = download_command(
            "https://example.invalid/artifact",
            Path::new("/tmp/artifact.download"),
        );
        let arguments = command
            .get_args()
            .map(|value| value.to_string_lossy().into_owned())
            .collect::<Vec<_>>();

        assert!(
            arguments
                .windows(2)
                .any(|pair| pair == ["--connect-timeout", "15"])
        );
        assert!(
            arguments
                .windows(2)
                .any(|pair| pair == ["--speed-limit", "1024"])
        );
        assert!(
            arguments
                .windows(2)
                .any(|pair| pair == ["--speed-time", "30"])
        );
        assert!(arguments.iter().any(|value| value == "--retry-all-errors"));
    }

    #[test]
    fn verifies_integrity_and_registry_signature_together() {
        let root = std::env::temp_dir().join(format!("claw-package-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let package = root.join("openclaw.tgz");
        let content = b"verified package fixture";
        fs::write(&package, content).unwrap();
        let integrity = format!("sha512-{}", BASE64.encode(Sha512::digest(content)));
        let signing_key = SigningKey::from_slice(&[7_u8; 32]).unwrap();
        let signed = format!("openclaw@test:{integrity}");
        let signature: Signature = signing_key.sign(signed.as_bytes());
        let public_key = signing_key.verifying_key().to_public_key_der().unwrap();
        let mut manifest = InstallManifest::test_fixture();
        manifest.openclaw_version = "test".into();
        manifest.openclaw_integrity = integrity;
        manifest.openclaw_signature = BASE64.encode(signature.to_der());
        manifest.npm_key_id = "SHA256:test".into();
        manifest.npm_public_key = BASE64.encode(public_key.as_bytes());

        verify_openclaw_package_result(&package, &manifest).unwrap();
        fs::write(&package, b"modified package fixture").unwrap();
        assert!(verify_openclaw_package_result(&package, &manifest).is_err());
        let _ = fs::remove_dir_all(root);
    }
}
