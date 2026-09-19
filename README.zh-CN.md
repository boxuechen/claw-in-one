<p align="center">
  <img src="docs/assets/clawinone-logo.png" width="128" alt="ClawInOne Logo">
</p>

<h1 align="center">ClawInOne</h1>

<p align="center"><strong>OpenClaw on Android, all in one.</strong></p>

<p align="center">
  <a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a>
</p>

ClawInOne 让一台 Android 手机同时成为运行 OpenClaw 的计算机，以及
OpenClaw 开发和操作的 Android 设备。

它在 Android 官方 AVF Debian 环境中运行固定版本的上游 OpenClaw
Gateway，再将它连接回同一台手机，用于开发、App 交付、交互式呈现和
权限受限的设备操作。

**无需 Root，无需 PC，无需单独部署服务器。**

## 演示

### 在手机上创建并运行 Godot 游戏

https://github.com/user-attachments/assets/7756f277-f69d-41f2-9174-87c53c7c8086

OpenClaw 在手机内创建 Godot 项目、构建 ARM64 APK、安装、在 VScreen 中
开始并实际试玩，然后启动真实 App；整个闭环都发生在同一台 Android 手机上。

### 在同一台手机上操作 Android App

https://github.com/user-attachments/assets/803950fd-f257-4e79-9e42-595eba5ff7c9

OpenClaw 使用 Calculator 计算 `379 x 4`，把结果 `1516` 带入 Calendar，
创建下周一 2:00 PM 的 **ClawInOne demo - 1516**，并确认事件已经保存。
这段短片剪自同一台物理手机上一次连续的 4 分 42 秒运行；过程中没有使用
桌面电脑或远程 Android 设备。

> **Alpha 版本：**当前固定使用 [OpenClaw 2026.9.4](UPSTREAM.md)，核心路径已在
> Pixel 8 / Android 17 上完成保留设备验证。全新设备 onboarding 和更广泛的
> 设备覆盖仍有待验证。

## 一台手机，一个完整闭环

```text
需求 -> 编码 -> 构建 -> 安装 -> 运行 -> 观察 -> 操作 -> 改进
```

![ClawInOne 同机架构](docs/assets/architecture.svg)

图中的所有组件都运行在同一台物理 Android 手机上。上游 OpenClaw 始终是
唯一的 Agent Runtime；ClawInOne 提供受管理的本地环境，以及进入 Android
的有界桥接能力。

## 已实现能力

下表中的“已验证”是指在 [Validation](docs/validation.md) 记录的 Pixel 8
保留设备基线上完成观察，并不代表所有 Android 设备都已获得支持。

| 能力 | 状态 | 用途 |
| --- | --- | --- |
| 本地 OpenClaw 托管 | 已验证 | 在 AVF Debian 中安装、校验、启动和恢复固定版本的上游 Gateway。 |
| Android 开发 | 已验证 | 在手机上完成 Kotlin、NDK、Flutter、Godot 和 React Native 构建。 |
| Web 开发 | 已验证 | 完成 React/TypeScript/Vite 构建、服务、精确端口反向映射，并在 Android Chrome 中打开和更新。 |
| App Delivery | 已验证 | 对 APK 进行绑定回执的检查、安装、启动、回读、截图和有界日志读取。 |
| VScreen | 已验证 | 由固定版本 scrcpy server 驱动的内置交互式 Android 虚拟屏幕。 |
| Terminal | 已验证 | 访问 OpenClaw 所使用的同一个 Debian 环境。 |
| Android Use | 已验证，可选启用 | 在明确授权后观察和操作精确匹配的 Android App。 |

当前版本不包含 Android Chrome CDP 自动化。Web 结果可以在真实 Android
Chrome 中打开，但浏览器自动化仍处于延后状态。

## 在手机上完成受管理的安装

ClawInOne 会检查设备能力，引导完成 Android 系统负责的 Linux 和开发者选项
步骤，连接签名的 Supervisor，并安装固定版本的 OpenClaw 环境。用户不需要
在 PC 或服务器上手动部署 Node、OpenClaw、Gateway 服务、ADB、Chromium
或 scrcpy。

系统授权仍然保持显式：官方 Linux 环境、Wireless debugging、AI 服务配置，
以及可选的 Android Use Accessibility 设置都可能需要用户操作。AI Provider
和依赖下载可能需要网络；ClawInOne 不宣称完全离线或静默安装。

## 设备兼容性

ClawInOne 根据实际能力判断兼容性，而不是维护手机型号白名单。目前要求：

- Android API 35 或更高版本，以及 ARM64（`arm64-v8a`）；
- Android Virtualization Framework 和官方系统 Linux Terminal；
- 可以启动的官方 Debian 环境；
- Developer options 和 Wireless debugging；
- 足够容纳所选开发 Profile 的存储和内存。

| 设备 | Android | 覆盖范围 |
| --- | --- | --- |
| Pixel 8（`shiba`） | Android 17 / API 37 | 完整保留设备基线 |

能够通过全部运行时探测的 Tensor Pixel——包括运行 Android 16 或更高版本的
Pixel 6 系列——属于预期兼容候选设备，目前还不是经过验证的支持声明。详见
[设备兼容性](docs/device-compatibility.md)。

## 快速开始

### 安装

从 [GitHub Releases](https://github.com/boxuechen/claw-in-one/releases)
下载已签名 APK 和 `SHA256SUMS`。设备要求和手机内引导流程见
[安装说明](docs/installation.md)。

### 从源码构建

```bash
git clone https://github.com/boxuechen/claw-in-one.git
cd claw-in-one/apps/android
./gradlew :app:assembleThirdPartyDebug
```

Debug APK 输出到 `apps/android/app/build/outputs/apk/thirdParty/debug/`。
完整依赖、安装方法和仓库验证命令见[源码构建](docs/building.md)。

## 技术文档

- [文档索引](docs/README.md)
- [安装说明](docs/installation.md)与[源码构建](docs/building.md)
- [产品架构](docs/architecture.md)
- [开发运行环境](docs/development-environment.md)
- [开发 Profile](docs/development-profiles.md)
- [Device Bridge](docs/device-bridge.md) 与 [App Delivery](docs/app-delivery.md)
- [VScreen](docs/vscreen.md)、[Android Use](docs/android-use.md) 与 [Terminal](docs/terminal.md)
- [验证证据](docs/validation.md) 与 [Roadmap](docs/roadmap.md)
- [Changelog](CHANGELOG.md)
- [维护者契约](docs/maintainers/README.md)

## 与 OpenClaw 的关系

ClawInOne 始终使用上游 OpenClaw 作为唯一的 Agent Runtime。Android App
基于上游 OpenClaw Android App；ClawInOne 在它周围增加同机运行、安装、开发、
交付、呈现和设备操作环境。

ClawInOne 是独立的社区项目，并非 OpenClaw 官方发行版。准确的上游来源见
[UPSTREAM.md](UPSTREAM.md)。

欢迎按照 [CONTRIBUTING.md](CONTRIBUTING.md) 参与贡献。安全问题请通过
[SECURITY.md](SECURITY.md) 中的私密渠道报告。

项目使用 [MIT License](LICENSE)，上游及依赖归属见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
