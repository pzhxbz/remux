# RemoteMux

[![CI](https://github.com/pzhxbz/remux/actions/workflows/release.yml/badge.svg)](https://github.com/pzhxbz/remux/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Status: Experimental](https://img.shields.io/badge/status-experimental-orange.svg)](#项目状态)

RemoteMux 是一套面向多台机器的远程 tmux 控制系统。每台受控机器运行普通用户权限的 Rust Agent，Agent 仅主动连接 Relay；Rust CLI 或 Android App 通过 Relay 发现机器、管理 tmux session/window/pane，并附着到完整的原生 tmux 终端。

# ⚠️ 纯 Vibe Coding 实验项目，不保证实现绝对正确

> [!CAUTION]
> 本项目完全以 vibe coding 方式开发，目前属于实验性原型。代码经过了自动化测试和有限环境验证，但这些检查不能证明协议、安全性、加密使用、并发逻辑、终端兼容性或数据处理绝对正确。请勿将其直接用于生产环境、关键基础设施、敏感凭证或不可恢复的数据。使用者必须自行审计代码、评估风险并承担使用后果。

## 项目状态

RemoteMux 已完成可端到端运行的 Rust 服务端/客户端、Linux Agent 和原生 Android MVP，并在 Android 12 模拟器 → Relay → OrbStack Ubuntu 24.04 → stock tmux 3.4 的链路上验证。它仍是早期实验项目，不承诺协议向后兼容，也没有完成生产级公网部署所需的全部安全能力。

已验证环境：

- Ubuntu 24.04 ARM64 / tmux 3.4 / Rust 1.85.0；
- macOS ARM64 / tmux 3.6a；
- Android 12 ARM64 emulator。

详细记录见 [验证报告](docs/validation.md)，目标架构与安全设计见 [设计文档](DESIGN.md)。

## 系统架构

```text
┌────────────────────┐       WebSocket       ┌──────────────────┐
│ Android App        │◀─────────────────────▶│                  │
│ Rust CLI Client    │                       │   remux-relay    │
└────────────────────┘                       │                  │
          │                                  └────────┬─────────┘
          │ E2EE command / terminal payload           │ WebSocket
          └───────────────────────────────────────────┤
                                                      ▼
                                            ┌──────────────────┐
                                            │ remux Agent      │
                                            │ PTY + stock tmux │
                                            └──────────────────┘
```

| 组件 | 产物 | 职责 |
|---|---|---|
| Agent | `remux` | 运行在受控机器，管理当前用户的 stock tmux，并提供 PTY attach |
| Relay | `remux-relay` | 鉴权、机器在线状态与有界密文转发，不持有机器 pairing key |
| CLI Client | `remux-client` | 发现机器，管理 session/window/pane，并以 raw terminal 附着 |
| Android App | APK | 多机器管理、terminal tabs、触摸历史、自有终端键盘与系统 IME |

## 已实现能力

### tmux 与远程终端

- 创建、列出、重命名和显式关闭 tmux session/window；
- 列出 pane，并在 attach 内直接创建和切换 window；
- 使用 PTY 和原生 `tmux attach-session`，不实现私有 tmux 协议；
- 原样传输 ANSI、True Color、UTF-8、CJK、控制键和 terminal resize；
- Client/Agent/Relay 断开只 detach，不终止已有 tmux session；
- 不创建、不修改、不 source 用户的 `.tmux.conf`；
- 管理协议不提供通用远程 shell/exec。

### Android

- 机器搜索、在线筛选、收藏、最近使用和 pairing 导入；
- session/window/pane 管理与最多 6 个保活 terminal tab；
- xterm.js 本地资源、20,000 行 scrollback、历史/应用手势切换和字体缩放；
- App 自有 terminal 键盘，精确支持 Ctrl-D、Ctrl-C、Ctrl/Alt、tmux prefix 和 Paste；
- `Fn` 层提供 F1–F12、Home/End、PgUp/PgDn、Ins/Del；
- `中/EN` 与 Android 系统 IME 互斥切换，支持中文 composition；
- bracketed paste，多行或大文本粘贴前确认；
- 竖屏紧凑布局、TalkBack 基础支持与 renderer 重建后的 tmux 重绘。

## 安全边界

当前实现使用每台机器独立的 256-bit pairing key，并通过 ChaCha20-Poly1305 保护 Client 与 Agent 之间的业务载荷；Relay 只看到路由元数据和密文。该设计尚未完成独立安全审计。

以下生产级能力尚未实现或未完成：

- 一次性 enrollment 与设备撤销；
- Ed25519/X25519 身份握手和前向保密；
- 完整重放窗口、持久化状态与系统化速率限制；
- 公网威胁模型下的渗透测试和第三方密码学审计。

Relay 默认以自签证书提供 `wss://`，agent 与客户端不校验该证书，因此传输只防被动窃听。若 relay 暴露在不可信网络上，请配置真实证书或在前面挂可信的 HTTPS/WSS 反向代理。`REMUX_APP_CONFIG`、Relay token、`agent.toml` 和 `pairing.toml` 都是高权限凭证，不要提交到版本库、CI 日志或发送给第三方。

## 快速开始

### 1. 启动 Relay

示例 secret 仅作占位，请替换为独立生成的高熵值：

```bash
remux-relay \
  --listen 0.0.0.0:8787 \
  --agent-token 'replace-with-agent-token' \
  --client-token 'replace-with-client-token'
```

Relay 默认就是 `wss://`：不指定证书时它会在启动时生成一张临时自签证书，无需任何 TLS 配置。Agent 与客户端不校验 relay 证书，因此自签、换端口、用裸 IP 连接都能正常工作。

Relay 会输出一行可粘贴到 Android App 的配置：

```text
REMUX_APP_CONFIG=wss://0.0.0.0:8787/~<url-safe-client-token>
```

`~` 后只是 URL-safe 编码，不是加密。监听 `0.0.0.0` 时请把其中的主机换成手机实际可达的地址，例如 `wss://203.0.113.10:8787/~...`。

可选参数：

- `--tls-cert` / `--tls-key`：改用你自己的 PEM 证书，替代自动生成的自签证书。
- `--no-tls`：退回明文 `ws://`，用于前面已经挂了 Caddy/Nginx 做 TLS 终止的场景。

> 安全边界：tmux 会话内容由 pairing key 端到端加密，relay 看不到明文。但由于不校验证书，这一层 TLS 只防被动窃听——能实施主动中间人的攻击者可以窃取 relay 的 agent/client token。若你的 relay 暴露在不可信网络上，请配 `--tls-cert` 使用真实证书并在前面做反代。

### 2. 配置受控机器

受控机器需要安装 stock tmux，然后运行：

```bash
remux config
remux doctor
remux run
```

自动化配置示例：

```bash
REMUX_AGENT_TOKEN='replace-with-agent-token' remux config \
  --relay wss://127.0.0.1:8787 \
  --name my-linux-host
```

配置文件默认位置：

- Linux：`$XDG_CONFIG_HOME/remux/agent.toml` 或 `~/.config/remux/agent.toml`；
- macOS：`~/Library/Application Support/RemoteMux/agent.toml`。

`agent.toml` 和同目录的 `pairing.toml` 会以 `0600` 创建。请通过安全渠道把 `pairing.toml` 导入客户端；它等价于该机器的远程终端控制凭证。

### 3. 使用 Rust Client

```bash
export REMUX_CLIENT_TOKEN='replace-with-client-token'

remux-client --relay wss://127.0.0.1:8787 machines
remux-client sessions --pairing pairing.toml
remux-client create-session --pairing pairing.toml --name codex --cwd /path/to/repo
remux-client windows --pairing pairing.toml --session-id '$0'
remux-client attach --pairing pairing.toml --session-id '$0'
```

attach 后所有 tmux 输入原样发送。Rust Client 自己的 detach 组合键是 `Ctrl+\`，松开后再按 `d`；这只关闭临时 client，不会结束 tmux session。

破坏性操作必须显式确认：

```bash
remux-client kill-window --pairing pairing.toml --window-id '@1' --confirm
remux-client kill-session --pairing pairing.toml --session-id '$0' --confirm
```

所有非交互管理命令均支持全局 `--json`。

## 本地构建

要求：Rust 1.98+；Android 构建要求 JDK 17 和 Android SDK。

Rust 开发构建与质量门禁：

```bash
cargo check
cargo clippy --all-targets -- -D warnings
cargo clippy --all-targets --all-features -- -D warnings
cargo test
```

Android：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Android Studio 可直接打开 `android/`。详细说明见 [android/README.md](android/README.md)。

Linux 分发使用 musl 静态链接，支持 x86_64 和 aarch64：

```bash
cargo install cargo-zigbuild --version 0.20.1 --locked
scripts/build-musl.sh x86_64-unknown-linux-musl
scripts/build-musl.sh aarch64-unknown-linux-musl
```

脚本会构建 `remux`、`remux-client` 和 `remux-relay`，检查 ELF 不包含动态 interpreter，并输出 `dist/remux-<target>.tar.gz`。

## 持续集成与发布

[GitHub Actions](.github/workflows/release.yml) 在 Pull Request、`main` push 和手动触发时自动执行：

- Rust check、两组 clippy `-D warnings` 和全部测试；
- Android unit test、lint、Debug APK 与签名 Release APK 构建；
- x86_64/aarch64 Linux musl 静态 Agent、Client 和 Relay 构建；
- Android APK 与 Linux 压缩包作为 Actions artifacts 上传。

推送 `v*` tag 时会创建 GitHub Release，仅上传签名后的 `remux-android-release.apk`、两种架构的静态压缩包与 `SHA256SUMS`。Debug APK 只保留为短期 Actions artifact，不会公开附加到 Release；Release APK 由仅在标签发布任务中可用的 GitHub Actions Secrets 自动签名并由 `apksigner` 验证。

仓库管理员需要配置 `REMUX_ANDROID_KEYSTORE_BASE64`、`REMUX_ANDROID_KEYSTORE_PASSWORD`、`REMUX_ANDROID_KEY_ALIAS` 和 `REMUX_ANDROID_KEY_PASSWORD` 四个 Actions Secrets。签名 keystore 必须离线备份；丢失后将无法用同一身份更新已安装的 Android 应用。Actions 的手动触发参数 `release_tag` 可用于从现有标签重新构建并补全 Release 附件。

## 仓库结构

```text
android/             原生 Kotlin / Jetpack Compose Android App
crates/agent/        受控机器 Agent（binary: remux）
crates/client/       Rust 验证客户端（binary: remux-client）
crates/protocol/     公共协议与端到端加密载荷
crates/relay/        WebSocket Relay（binary: remux-relay）
docs/                交互规范与验证记录
scripts/             musl 静态构建脚本
DESIGN.md            架构、安全边界与协议设计
```

## 行为保证

- Agent 只访问启动它的 OS 用户能够访问的默认 tmux server；
- Agent 不监听受控机器的入站端口，只发起 Relay WebSocket 出站连接；
- 普通 detach、Client 断开、Relay 断开和 Agent 退出都不会调用 `kill-session`；
- 只有用户显式发出 `KillSession`/`KillWindow` 请求才执行相应 tmux 命令；
- 新 session 只启动用户默认 shell，协议不接受任意命令字符串。

## 文档

- [完整设计与安全模型](DESIGN.md)
- [Android 使用说明](android/README.md)
- [Android terminal 交互规范](docs/android-interaction.md)
- [Linux/macOS/Android 验证记录](docs/validation.md)

## License

[MIT](LICENSE)。本许可证不构成正确性、安全性、适销性或特定用途适用性的保证。
