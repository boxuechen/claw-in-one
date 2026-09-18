# Upstream provenance

ClawInOne 的 Android App 基于 [OpenClaw](https://github.com/openclaw/openclaw) 官方源码，但作为独立第三方客户端演进。

| 用途 | 上游版本 | 上游提交 | 本仓库状态 |
| --- | --- | --- | --- |
| Android 初始基线 | `v2026.8.1` | `ea806575e6450e4d1efdfc72c19f04be982a1b9b` | 基线提交 `1f262b4` |
| 当前参考快照 | `v2026.9.4` | `3a9d69db306cd7f081e06254cb89c4bcc14a7107` | 签名发布标签的只读快照 |
| Gateway Protocol | `v2026.9.4` | `3a9d69db306cd7f081e06254cb89c4bcc14a7107` | 显式同步上游 Android 生成产物 |
| Debian Runtime | `2026.9.4` | npm 固定发布物 | 由 Bootstrap 验签并原子激活 |

Android 基线导入范围为 `apps/android/`，并携带共享构建资源
`apps/shared/OpenClawKit/Sources/OpenClawKit/Resources/tool-display.json`。

基线后的 Android 提交来自 ClawInOne R1 真机验证分支，并保留原提交顺序和作者信息。最近一次显式导入以独立 Fork 的提交
`57da11f7e336d4a1d9be44804fed682ed548990e` 为边界，对应迁移标签
`claw-in-one-android-migration-2026-09-03`；导入后的冲突收敛与产品能力整合只在本仓库继续。

`v2026.8.2` 到 `v2026.9.4` 保持 Gateway protocol v4，但增加了方法、事件、字段和枚举，并显著调整
Gateway 连接、设备认证、TLS 与重连实现。本仓库不重新导入 `apps/android/`；只同步签名标签内现成的协议生成产物，
并按 ClawInOne 的本地连接边界选择性吸收必要修复。产品插件与发布 Runtime 精确绑定到 `2026.9.4`，不保留
`2026.8.2` 运行分支、协议 fallback 或兼容适配器。

固定的 npm Runtime 是 OpenClaw 平台基线；`product-plugins/` 是 ClawInOne 产品实现的唯一源码。
Bootstrap 将后者随版本化 Supervisor 发布物部署；Gateway 启动前只把这些验签的一方产品
Plugin 原子安装到 Runtime 的明确 bundled slots，替换同 ID 基线副本并清除旧的产品加载路径。
因此每个产品 Plugin 只有一个生效实现，同时保留 OpenClaw 对 host-bundled Plugin 的权限边界。
相邻 `../openclaw` 仍只用于只读参考，不参与构建、部署或覆盖。

## 仓库边界

- `claw-in-one` 是产品源码的唯一所有者，长期维护 Android App、Bootstrap、测试、发布流程与产品文档。
- `boxuechen/openclaw` 是独立的完整 Fork，只用于跟踪上游、验证差异和准备上游贡献。
- 两个仓库在本地使用并列目录；ClawInOne 不嵌套 Fork、Git worktree 或 Git submodule，也不在构建时读取相邻仓库。
- 上游代码只能通过带来源 Commit 的显式导入进入本仓库；不自动合并、不在构建时同步，也不承诺兼容任意 Gateway 版本。

`apps/android/gradlew` 可直接完成 Android 构建和测试。导入时携带的 pnpm、Fastlane、Google Play 元数据和私有签名仓库入口已经移除；正式发布流程建立后必须由本仓库重新实现。

ClawInOne 是独立社区项目，不是 OpenClaw 官方发行版。
