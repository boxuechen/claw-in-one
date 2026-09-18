import fs from "node:fs";
import path from "node:path";
import {
  ProjectWorkspaceError,
  ProjectWorkspaceErrorCode,
} from "./protocol.mjs";

const ANDROID_KOTLIN_PROFILE_ID = "android-kotlin-compose-v1";
const ANDROID_NATIVE_PROFILE_ID = "android-native-vulkan-v1";
const FLUTTER_PROFILE_ID = "flutter-android-v1";
const GODOT_ANDROID_PROFILE_ID = "godot-android-v1";
const REACT_NATIVE_PROFILE_ID = "react-native-android-v1";
const WEB_DEVELOPMENT_PROFILE_ID = "web-development-v1";
const SHA256 = /^[a-f0-9]{64}$/u;

function regularFile(fileSystem, target) {
  const stat = fileSystem.statSync(target);
  return stat.isFile() ? stat : null;
}

/** Reads the Supervisor-published environment and immutable profile at decision time. */
export function createSupervisorCapabilityReadiness(options = {}) {
  const environment = options.environment ?? process.env;
  const fileSystem = options.fileSystem ?? fs;

  function androidKotlin() {
    try {
      const profilePath = environment.CLAW_IN_ONE_ANDROID_PROFILE?.trim();
      const materializerPath = environment.CLAW_IN_ONE_ANDROID_NEW_PROJECT?.trim();
      if (!profilePath || !materializerPath || !path.isAbsolute(profilePath) || !path.isAbsolute(materializerPath)) {
        return null;
      }
      const resolvedProfile = fileSystem.realpathSync(profilePath);
      const resolvedMaterializer = fileSystem.realpathSync(materializerPath);
      if (path.dirname(resolvedProfile) !== path.dirname(resolvedMaterializer)) return null;
      const profileStat = regularFile(fileSystem, resolvedProfile);
      const materializerStat = regularFile(fileSystem, resolvedMaterializer);
      if (
        !profileStat ||
        !materializerStat ||
        (profileStat.mode & 0o222) !== 0 ||
        (materializerStat.mode & 0o111) === 0
      ) {
        return null;
      }

      const profile = JSON.parse(fileSystem.readFileSync(resolvedProfile, "utf8"));
      const releasePath = path.join(path.dirname(resolvedProfile), "release.json");
      const release = JSON.parse(fileSystem.readFileSync(releasePath, "utf8"));
      const qualified = fileSystem
        .readFileSync(path.join(path.dirname(resolvedProfile), "qualified"), "utf8")
        .trim()
        .toLowerCase();
      const declaredMaterializer = fileSystem.realpathSync(profile.materializer);
      const sdkRoot = environment.ANDROID_SDK_ROOT?.trim();
      const javaHome = environment.JAVA_HOME?.trim();
      if (
        profile.schemaVersion !== 1 ||
        profile.profileId !== ANDROID_KOTLIN_PROFILE_ID ||
        profile.status !== "ready" ||
        !Number.isSafeInteger(profile.generation) ||
        profile.generation < 1 ||
        release.schemaVersion !== 1 ||
        release.profileId !== profile.profileId ||
        release.generation !== profile.generation ||
        declaredMaterializer !== resolvedMaterializer ||
        profile.androidSdkRoot !== sdkRoot ||
        profile.javaHome !== javaHome ||
        !SHA256.test(qualified) ||
        profile.debugSignerSha256?.toLowerCase() !== qualified
      ) {
        return null;
      }
      return Object.freeze({
        profileId: profile.profileId,
        generation: profile.generation,
      });
    } catch {
      return null;
    }
  }

  function flutter() {
    try {
      const profilePath = environment.CLAW_IN_ONE_FLUTTER_PROFILE?.trim();
      const materializerPath = environment.CLAW_IN_ONE_FLUTTER_NEW_PROJECT?.trim();
      if (!profilePath || !materializerPath || !path.isAbsolute(profilePath) || !path.isAbsolute(materializerPath)) {
        return null;
      }
      const resolvedProfile = fileSystem.realpathSync(profilePath);
      const resolvedMaterializer = fileSystem.realpathSync(materializerPath);
      if (path.dirname(resolvedProfile) !== path.dirname(resolvedMaterializer)) return null;
      const profileStat = regularFile(fileSystem, resolvedProfile);
      const materializerStat = regularFile(fileSystem, resolvedMaterializer);
      if (
        !profileStat ||
        !materializerStat ||
        (profileStat.mode & 0o222) !== 0 ||
        (materializerStat.mode & 0o111) === 0
      ) {
        return null;
      }
      const profile = JSON.parse(fileSystem.readFileSync(resolvedProfile, "utf8"));
      const release = JSON.parse(
        fileSystem.readFileSync(path.join(path.dirname(resolvedProfile), "release.json"), "utf8"),
      );
      const qualified = fileSystem
        .readFileSync(path.join(path.dirname(resolvedProfile), "qualified"), "utf8")
        .trim()
        .toLowerCase();
      const declaredMaterializer = fileSystem.realpathSync(profile.materializer);
      if (
        profile.schemaVersion !== 1 ||
        profile.profileId !== FLUTTER_PROFILE_ID ||
        profile.status !== "ready" ||
        profile.targetPlatform !== "android-arm64" ||
        !Number.isSafeInteger(profile.generation) ||
        profile.generation < 1 ||
        release.schemaVersion !== 1 ||
        release.profileId !== profile.profileId ||
        release.generation !== profile.generation ||
        release.flutterVersion !== profile.flutterVersion ||
        release.dartVersion !== profile.dartVersion ||
        release.frameworkRevision !== profile.frameworkRevision ||
        release.engineRevision !== profile.engineRevision ||
        release.androidNdk !== profile.androidNdk ||
        declaredMaterializer !== resolvedMaterializer ||
        profile.androidSdkRoot !== environment.ANDROID_SDK_ROOT?.trim() ||
        profile.javaHome !== environment.JAVA_HOME?.trim() ||
        !SHA256.test(qualified)
      ) {
        return null;
      }
      return Object.freeze({
        profileId: profile.profileId,
        generation: profile.generation,
      });
    } catch {
      return null;
    }
  }

  function androidNative() {
    try {
      const profilePath = environment.CLAW_IN_ONE_ANDROID_NATIVE_PROFILE?.trim();
      const materializerPath = environment.CLAW_IN_ONE_ANDROID_NATIVE_NEW_PROJECT?.trim();
      if (!profilePath || !materializerPath || !path.isAbsolute(profilePath) || !path.isAbsolute(materializerPath)) {
        return null;
      }
      const resolvedProfile = fileSystem.realpathSync(profilePath);
      const resolvedMaterializer = fileSystem.realpathSync(materializerPath);
      if (path.dirname(resolvedProfile) !== path.dirname(resolvedMaterializer)) return null;
      const profileStat = regularFile(fileSystem, resolvedProfile);
      const materializerStat = regularFile(fileSystem, resolvedMaterializer);
      if (!profileStat || !materializerStat || (profileStat.mode & 0o222) !== 0 || (materializerStat.mode & 0o111) === 0) {
        return null;
      }
      const profile = JSON.parse(fileSystem.readFileSync(resolvedProfile, "utf8"));
      const release = JSON.parse(
        fileSystem.readFileSync(path.join(path.dirname(resolvedProfile), "release.json"), "utf8"),
      );
      const qualified = fileSystem
        .readFileSync(path.join(path.dirname(resolvedProfile), "qualified"), "utf8")
        .trim()
        .toLowerCase();
      const declaredMaterializer = fileSystem.realpathSync(profile.materializer);
      if (
        profile.schemaVersion !== 1 ||
        profile.profileId !== ANDROID_NATIVE_PROFILE_ID ||
        profile.status !== "ready" ||
        profile.abi !== "arm64-v8a" ||
        !Number.isSafeInteger(profile.generation) ||
        profile.generation < 1 ||
        release.schemaVersion !== 1 ||
        release.profileId !== profile.profileId ||
        release.generation !== profile.generation ||
        release.androidNdk !== profile.androidNdk ||
        release.cmake !== profile.cmake ||
        release.abi !== profile.abi ||
        declaredMaterializer !== resolvedMaterializer ||
        profile.androidSdkRoot !== environment.ANDROID_SDK_ROOT?.trim() ||
        profile.javaHome !== environment.JAVA_HOME?.trim() ||
        !SHA256.test(qualified) ||
        profile.debugSignerSha256?.toLowerCase() !== qualified
      ) {
        return null;
      }
      return Object.freeze({ profileId: profile.profileId, generation: profile.generation });
    } catch {
      return null;
    }
  }

  function godotAndroid() {
    try {
      const profilePath = environment.CLAW_IN_ONE_GODOT_ANDROID_PROFILE?.trim();
      const materializerPath = environment.CLAW_IN_ONE_GODOT_ANDROID_NEW_PROJECT?.trim();
      if (!profilePath || !materializerPath || !path.isAbsolute(profilePath) || !path.isAbsolute(materializerPath)) {
        return null;
      }
      const resolvedProfile = fileSystem.realpathSync(profilePath);
      const resolvedMaterializer = fileSystem.realpathSync(materializerPath);
      if (path.dirname(resolvedProfile) !== path.dirname(resolvedMaterializer)) return null;
      const profileStat = regularFile(fileSystem, resolvedProfile);
      const materializerStat = regularFile(fileSystem, resolvedMaterializer);
      if (!profileStat || !materializerStat || (profileStat.mode & 0o222) !== 0 || (materializerStat.mode & 0o111) === 0) {
        return null;
      }
      const profile = JSON.parse(fileSystem.readFileSync(resolvedProfile, "utf8"));
      const release = JSON.parse(
        fileSystem.readFileSync(path.join(path.dirname(resolvedProfile), "release.json"), "utf8"),
      );
      const qualified = fileSystem
        .readFileSync(path.join(path.dirname(resolvedProfile), "qualified"), "utf8")
        .trim()
        .toLowerCase();
      const declaredMaterializer = fileSystem.realpathSync(profile.materializer);
      if (
        profile.schemaVersion !== 1 ||
        profile.profileId !== GODOT_ANDROID_PROFILE_ID ||
        profile.status !== "ready" ||
        profile.targetPlatform !== "android-arm64" ||
        profile.renderer !== "mobile" ||
        !Number.isSafeInteger(profile.generation) ||
        profile.generation < 1 ||
        release.schemaVersion !== 1 ||
        release.profileId !== profile.profileId ||
        release.generation !== profile.generation ||
        release.godotVersion !== profile.godotVersion ||
        release.godotBuild !== profile.godotBuild ||
        release.targetPlatform !== profile.targetPlatform ||
        release.renderer !== profile.renderer ||
        release.customBuild !== false ||
        declaredMaterializer !== resolvedMaterializer ||
        profile.androidSdkRoot !== environment.ANDROID_SDK_ROOT?.trim() ||
        profile.javaHome !== environment.JAVA_HOME?.trim() ||
        !path.isAbsolute(profile.godotBinary) ||
        !path.isAbsolute(profile.androidDebugTemplate) ||
        !regularFile(fileSystem, fileSystem.realpathSync(profile.godotBinary)) ||
        !regularFile(fileSystem, fileSystem.realpathSync(profile.androidDebugTemplate)) ||
        !SHA256.test(qualified) ||
        profile.debugSignerSha256?.toLowerCase() !== qualified
      ) {
        return null;
      }
      return Object.freeze({ profileId: profile.profileId, generation: profile.generation });
    } catch {
      return null;
    }
  }

  function reactNative() {
    try {
      const profilePath = environment.CLAW_IN_ONE_REACT_NATIVE_PROFILE?.trim();
      const materializerPath = environment.CLAW_IN_ONE_REACT_NATIVE_NEW_PROJECT?.trim();
      if (!profilePath || !materializerPath || !path.isAbsolute(profilePath) || !path.isAbsolute(materializerPath)) {
        return null;
      }
      const resolvedProfile = fileSystem.realpathSync(profilePath);
      const resolvedMaterializer = fileSystem.realpathSync(materializerPath);
      if (path.dirname(resolvedProfile) !== path.dirname(resolvedMaterializer)) return null;
      const profileStat = regularFile(fileSystem, resolvedProfile);
      const materializerStat = regularFile(fileSystem, resolvedMaterializer);
      if (!profileStat || !materializerStat || (profileStat.mode & 0o222) !== 0 || (materializerStat.mode & 0o111) === 0) {
        return null;
      }
      const profile = JSON.parse(fileSystem.readFileSync(resolvedProfile, "utf8"));
      const release = JSON.parse(
        fileSystem.readFileSync(path.join(path.dirname(resolvedProfile), "release.json"), "utf8"),
      );
      const qualified = fileSystem
        .readFileSync(path.join(path.dirname(resolvedProfile), "qualified"), "utf8")
        .trim()
        .toLowerCase();
      const declaredMaterializer = fileSystem.realpathSync(profile.materializer);
      const nodeBinary = fileSystem.realpathSync(profile.nodeBinary);
      const npmBinary = fileSystem.realpathSync(profile.npmBinary);
      if (
        profile.schemaVersion !== 1 ||
        profile.profileId !== REACT_NATIVE_PROFILE_ID ||
        profile.status !== "ready" ||
        profile.targetPlatform !== "android-arm64" ||
        profile.newArchitecture !== true ||
        profile.hermes !== true ||
        profile.metroRequired !== false ||
        !Number.isSafeInteger(profile.generation) ||
        profile.generation < 1 ||
        release.schemaVersion !== 1 ||
        release.profileId !== profile.profileId ||
        release.generation !== profile.generation ||
        release.reactNativeVersion !== profile.reactNativeVersion ||
        release.reactVersion !== profile.reactVersion ||
        release.nodeVersion !== profile.nodeVersion ||
        release.newArchitecture !== profile.newArchitecture ||
        release.hermes !== profile.hermes ||
        release.metroRequired !== profile.metroRequired ||
        declaredMaterializer !== resolvedMaterializer ||
        profile.androidSdkRoot !== environment.ANDROID_SDK_ROOT?.trim() ||
        profile.javaHome !== environment.JAVA_HOME?.trim() ||
        !path.isAbsolute(profile.nodeBinary) ||
        !path.isAbsolute(profile.npmBinary) ||
        !path.isAbsolute(profile.npmCache) ||
        !regularFile(fileSystem, nodeBinary) ||
        !regularFile(fileSystem, npmBinary) ||
        !SHA256.test(qualified) ||
        profile.debugSignerSha256?.toLowerCase() !== qualified
      ) {
        return null;
      }
      return Object.freeze({ profileId: profile.profileId, generation: profile.generation });
    } catch {
      return null;
    }
  }

  function webDevelopment() {
    try {
      const profilePath = environment.CLAW_IN_ONE_WEB_PROFILE?.trim();
      const materializerPath = environment.CLAW_IN_ONE_WEB_NEW_PROJECT?.trim();
      const builderPath = environment.CLAW_IN_ONE_WEB_BUILD_PROJECT?.trim();
      const serverPath = environment.CLAW_IN_ONE_WEB_SERVE_PROJECT?.trim();
      if ([profilePath, materializerPath, builderPath, serverPath].some(value => !value || !path.isAbsolute(value))) return null;
      const resolvedProfile = fileSystem.realpathSync(profilePath);
      const entries = [materializerPath, builderPath, serverPath].map(value => fileSystem.realpathSync(value));
      if (entries.some(value => path.dirname(value) !== path.dirname(resolvedProfile))) return null;
      const [materializer, builder, server] = entries.map(value => regularFile(fileSystem, value));
      const profileStat = regularFile(fileSystem, resolvedProfile);
      if (
        !profileStat || !materializer || !builder || !server ||
        (profileStat.mode & 0o222) !== 0 ||
        [materializer, builder, server].some(value => (value.mode & 0o111) === 0)
      ) return null;
      const profile = JSON.parse(fileSystem.readFileSync(resolvedProfile, "utf8"));
      const release = JSON.parse(fileSystem.readFileSync(path.join(path.dirname(resolvedProfile), "release.json"), "utf8"));
      const qualified = fileSystem.readFileSync(path.join(path.dirname(resolvedProfile), "qualified"), "utf8").trim().toLowerCase();
      if (
        profile.schemaVersion !== 1 || profile.profileId !== WEB_DEVELOPMENT_PROFILE_ID ||
        profile.status !== "ready" || profile.generation !== 1 || profile.productionBuild !== true ||
        profile.serverHost !== "127.0.0.1" || profile.cdpRequired !== false ||
        release.schemaVersion !== 1 || release.profileId !== profile.profileId ||
        release.generation !== profile.generation || release.nodeVersion !== profile.nodeVersion ||
        release.npmVersion !== profile.npmVersion || release.host !== profile.serverHost ||
        release.productionBuild !== profile.productionBuild || release.cdpRequired !== profile.cdpRequired ||
        release.reactVersion !== profile.reactVersion || release.reactDomVersion !== profile.reactDomVersion ||
        release.viteVersion !== profile.viteVersion || release.typescriptVersion !== profile.typescriptVersion ||
        release.packageLockSha256 !== qualified || !SHA256.test(qualified) ||
        fileSystem.realpathSync(profile.materializer) !== entries[0] ||
        fileSystem.realpathSync(profile.builder) !== entries[1] ||
        fileSystem.realpathSync(profile.server) !== entries[2] ||
        !path.isAbsolute(profile.builder) || profile.buildCommand !== `${profile.builder} --project-dir .` ||
        profile.artifact !== "dist/index.html" ||
        !path.isAbsolute(profile.npmCache) ||
        !regularFile(fileSystem, fileSystem.realpathSync(profile.nodeBinary)) ||
        !regularFile(fileSystem, fileSystem.realpathSync(profile.npmBinary))
      ) return null;
      return Object.freeze({profileId: profile.profileId, generation: profile.generation});
    } catch {
      return null;
    }
  }

  function current() {
    const android = androidKotlin();
    const nativeProfile = androidNative();
    const flutterProfile = flutter();
    const godotProfile = godotAndroid();
    const reactNativeProfile = reactNative();
    const webProfile = webDevelopment();
    if (!android && !nativeProfile && !flutterProfile && !godotProfile && !reactNativeProfile && !webProfile) return null;
    const readyCapabilities = [
      ...(android ? ["android_kotlin"] : []),
      ...(nativeProfile ? ["android_native"] : []),
      ...(flutterProfile ? ["flutter"] : []),
      ...(godotProfile ? ["godot_android"] : []),
      ...(reactNativeProfile ? ["react_native"] : []),
      ...(webProfile ? ["web_development"] : []),
    ];
    return Object.freeze({
      revision: [
        android ? `${android.profileId}:${android.generation}` : "android-kotlin:none",
        nativeProfile ? `${nativeProfile.profileId}:${nativeProfile.generation}` : "android-native:none",
        flutterProfile ? `${flutterProfile.profileId}:${flutterProfile.generation}` : "flutter:none",
        godotProfile ? `${godotProfile.profileId}:${godotProfile.generation}` : "godot-android:none",
        reactNativeProfile ? `${reactNativeProfile.profileId}:${reactNativeProfile.generation}` : "react-native:none",
        webProfile ? `${webProfile.profileId}:${webProfile.generation}` : "web-development:none",
      ].join("|"),
      readyCapabilities: Object.freeze(readyCapabilities),
      profiles: Object.freeze({
        androidKotlin: android,
        androidNative: nativeProfile,
        flutter: flutterProfile,
        godotAndroid: godotProfile,
        reactNative: reactNativeProfile,
        webDevelopment: webProfile,
      }),
    });
  }

  return Object.freeze({
    current,
    androidKotlin,
    androidNative,
    flutter,
    godotAndroid,
    reactNative,
    webDevelopment,
    isReady: () => current() !== null,
    requireCurrent() {
      const snapshot = current();
      if (snapshot) return snapshot;
      throw new ProjectWorkspaceError(
        ProjectWorkspaceErrorCode.unavailable,
        "The verified development profile is unavailable",
        { retryable: true },
      );
    },
  });
}
