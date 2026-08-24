# Rust 与 Android 端到端验证记录

> **注意（2026-08，protocol v2）**：本文记录的是 protocol v1（含 pairing.toml 与 ChaCha20-Poly1305 端到端加密）时期的验证结果。v2 已移除 pairing 与 payload 加密（客户端仅凭 client token 管理所有在线机器），其中涉及 pairing TOML、crypto 向量的条目不再适用，仅作为历史记录保留。


验证日期：2026-08-23。

## 环境

| 环境 | 架构 | tmux | Rust | 用途 |
|---|---|---|---|---|
| macOS 本机 | arm64 | 3.6a | 1.98.0 | 开发回归、Codex/Claude CLI 可见性 |
| OrbStack Ubuntu 24.04 | arm64 | 3.4 | 1.85.0 | 主要 Linux/MSRV/PTY 兼容性验证 |
| Alpine musl 容器 | arm64 | N/A | 1.85.1 musl host | 静态分发构建与执行 |
| Alpine/Debian 容器 | x86_64 | N/A | 1.85.1 musl host | 静态分发及 glibc 系统执行验证 |
| Android 12 模拟器 | arm64 / API 31 | N/A | Kotlin/JDK 17 | Android UI、WebView、触摸、IME 与旋转验证 |

OrbStack 使用了专门创建的 `remux-android-test` 虚拟机，没有复用或修改用户已有虚拟机。Agent 采用 `aarch64-unknown-linux-musl` 静态 binary，tmux 保持 stock 配置。

## 已通过项目

### 构建和单元测试

- Rust 1.85.0 下 `cargo build --workspace` 通过；
- macOS 和 Linux 下 `cargo test --workspace` 通过；
- ChaCha20-Poly1305 round-trip 与 AAD 绑定测试通过；
- tmux ID 校验、固定格式解析、Client detach escape 测试通过。
- Android protocol/crypto/TOML/key encoder/Relay mock 单元测试通过；`assembleDebug`、`assembleRelease` 和 `lintDebug` 通过。
- Relay Quick Connect 生成/解析、Rust/Android `terminal_select_window` wire format 单元测试通过。

Linux/MSRV 测试发现 Rust 1.85 不支持代码中最初使用的 let-chain 写法，现已改为兼容写法。这是独立 Linux 构建实际发现并修复的差异。

### TOML 配置与命令入口

- Agent 主程序名为 `remux`，入口为 `remux config`、`remux doctor`、`remux run`；
- `remux config` 的 flags/env 非交互模式已实际运行；无 flags 时会交互询问 Relay URL 和隐藏输入 token；
- `agent.toml` 和 `pairing.toml` round-trip、`0600` 权限以及 Client 读取 pairing TOML 已验证；
- TOML 配置下重新跑通 Relay → Agent → Client 的 session 创建、查询和关闭。

### musl 静态分发

- 用 Rust 1.85.1 的原生 `aarch64-unknown-linux-musl` host 构建三个 release binary；
- 用 Rust 1.85.1 的原生 `x86_64-unknown-linux-musl` host 构建三个 release binary；
- `file` 分别识别为 `statically linked` 和 `static-pie linked`、stripped ELF；
- ELF program headers 无 `PT_INTERP`，dynamic section 无 `NEEDED`；
- ARM64 binary 在 Alpine 执行，x86_64 binary 在 Alpine 和不含 musl loader 的 Debian/glibc 容器执行；
- 两个架构均已生成含 `remux`、`remux-client`、`remux-relay` 的 tar.gz 和 SHA-256 校验；
- musl 构建脚本通过 `bash -n`，GitHub Actions workflow 通过 YAML 解析。CI 尚未在真实 GitHub runner 上触发。

### Relay → Agent → tmux 管理

在 macOS tmux 3.6a 和 Linux tmux 3.4 上都完成：

- 机器上线与发现；
- 无 tmux server 时返回空 session 列表；
- 创建、列出、重命名和关闭 session；
- 创建、列出、重命名和关闭 window；
- 列出 pane 的 ID、命令、cwd 和尺寸；
- 未带 `--confirm` 的 kill 操作被 Client 拒绝；
- session/window 参数按 `$N`/`@N` 内部 ID 校验。
- attach stream 的 window 切换只允许目标 session 内的 `@window_id`，并使用精确 client tty 调用 stock tmux `switch-client`。

### raw terminal

Linux 的实际 PTY attach 验证了：

- ANSI color；
- UTF-8 中文；
- 完整 tmux status/UI 重绘；
- stdin/stdout 双向转发；
- `Ctrl-b c` 原样进入 tmux 并新建窗口；
- 第一条远程连接使用正常尺寸；
- 已有 client 时第二条连接返回 `ignore-size=true`；
- `take_control` attach 从 80×24 resize 到 112×41 后，Linux tmux pane 实际变为 112×40（扣除状态栏）；
- 两个 Client 依次 detach 后 session 仍存在且 attached client 数回到 0。

macOS attach 路径还验证了 `codex --version` 和 `claude --version` 的远程执行输出，分别识别到 Codex CLI 与 Claude Code。当前验证没有把真实账户对话写入测试记录。

### Android → Relay → Linux tmux

在 API 31 arm64 模拟器到 OrbStack Ubuntu 24.04/tmux 3.4 的真实链路上完成：

- 配置 Relay、导入 `pairing.toml`、发现在线 Linux 机器；
- 从 App 创建 session，显示完整 tmux status、ANSI 和 UTF-8 输出；
- 输入 `echo terminal-input-ok` 到 Linux PTY 并正确回显；
- 运行 `sleep 30` 后点击独立 `^C`，pane 前台命令恢复为 shell；
- 连续输出 80 行后上滑进入 History，新输出不抢视口并显示未读行数；支持惯性滑动、整页前后翻动和一键回到 Live；
- 手动旋转后新的 WebView 通过 `terminal_refresh`/`tmux refresh-client` 自动恢复完整画面；最终交互保持设备方向，不再强制 attach 横屏；
- 竖屏无键盘时使用 56dp 单行 terminal tab、紧凑 window 行和 12px 默认 terminal 字号；不再常驻额外快捷栏；
- 点击 xterm 默认打开 App 自有 QWERTY terminal 键盘并隐藏 window/status 区，实测 tmux 保持 `46×30`；Paste 位于首屏高频行；
- 在自有键盘输入 `cat` 后依次点 Ctrl、`d`，Linux pane 的前台命令从 `cat` 恢复为 `bash`，验证发送单字节 `0x04` 而非字符 `d`；
- 点击 `Fn` 后原位显示 F1–F12、Home/End、PgUp/PgDn、Ins/Del，不增加键盘高度；
- 点击 `中/EN` 仅显示 Gboard，Android Back 收起后可重新打开自有键盘；快速切换测试中 `mInputShown=false` 时仅存在自有键盘，未再出现双层键盘；
- attach 内点击 `+ Window` 在 Linux tmux 创建 `@2` 并自动切换；点击 `0:bash`、`1:second`、`2:bash` chips 时目标 window 的 active 状态与 App 一致；
- Relay 启动实际输出 `REMUX_APP_CONFIG=ws://192.168.31.233:18787/~...`；App 分别以整行输出和 `192.168.31.233:18787/secret` 简写粘贴重连，机器 pairing 保持且恢复 `1 online`；
- 强制停止 App 后 Linux session 保持 `1 window / 0 attached`。

测试中发现直接向 tmux attach 子进程发送 SIGHUP 会在 stock tmux 3.4 上连带销毁 server。实现已经改为记录 PTY tty，并使用精确的 `tmux detach-client -t <tty>`；新增隔离 socket 集成测试确保 detach 后 session 存活。

### 故障行为

- 停止 Agent 后，Relay 机器列表变为 offline，Linux tmux session 继续运行；
- 重新启动 Agent 后，原 session 被重新发现；
- 停止并重启 Relay 后，Agent 按指数退避自动重连；
- Relay 停止期间 tmux session/window 数量与内容不受影响。

### 零 tmux 配置改动

干净 Linux 用户在 Agent 初始化、管理、attach、多客户端和断线测试前后均不存在 `~/.tmux.conf`，Agent 没有创建它。代码扫描确认没有 `source-file`、全局 `set-option`、tmux socket 私有协议或通用远程 exec 实现。

## 尚未通过/尚未实现

- 公网 WSS/Caddy 部署；
- macOS x86_64 构建；
- Android 真机上的多种厂商 IME、蓝牙键盘和完整 TalkBack 验收；
- OSC 52 剪贴板授权与 terminal 文本选择专项测试；
- 多小时大输出、六个并行 tab 和低内存回收压力测试；
- Agent kill -9 与长时间压力/慢消费者测试；
- 一次性 enrollment、身份签名握手、前向保密、重放保护与客户端撤销；
- 15 秒断线 grace period 和 terminal stream resume；
- systemd user/launchd 安装器；
- 多个 named/explicit tmux endpoint。

当前结果证明了 stock tmux + PTY + 出站 Relay + Rust Client 的核心技术路径，不代表生产安全验收完成。
