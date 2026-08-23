# Rust 端到端验证记录

验证日期：2026-08-23。

## 环境

| 环境 | 架构 | tmux | Rust | 用途 |
|---|---|---|---|---|
| macOS 本机 | arm64 | 3.6a | 1.98.0 | 开发回归、Codex/Claude CLI 可见性 |
| OrbStack Ubuntu 24.04 | arm64 | 3.4 | 1.85.0 | 主要 Linux/MSRV/PTY 兼容性验证 |
| Alpine musl 容器 | arm64 | N/A | 1.85.1 musl host | 静态分发构建与执行 |
| Alpine/Debian 容器 | x86_64 | N/A | 1.85.1 musl host | 静态分发及 glibc 系统执行验证 |

OrbStack 使用了专门创建的 `remux-e2e` 虚拟机，没有复用或修改用户已有虚拟机；测试完成后该虚拟机已删除。

## 已通过项目

### 构建和单元测试

- Rust 1.85.0 下 `cargo build --workspace` 通过；
- macOS 和 Linux 下 `cargo test --workspace` 通过；
- ChaCha20-Poly1305 round-trip 与 AAD 绑定测试通过；
- tmux ID 校验、固定格式解析、Client detach escape 测试通过。

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

### raw terminal

Linux 的实际 PTY attach 验证了：

- ANSI color；
- UTF-8 中文；
- 完整 tmux status/UI 重绘；
- stdin/stdout 双向转发；
- `Ctrl-b c` 原样进入 tmux 并新建窗口；
- 第一条远程连接使用正常尺寸；
- 已有 client 时第二条连接返回 `ignore-size=true`；
- 两个 Client 依次 detach 后 session 仍存在且 attached client 数回到 0。

macOS attach 路径还验证了 `codex --version` 和 `claude --version` 的远程执行输出，分别识别到 Codex CLI 与 Claude Code。当前验证没有把真实账户对话写入测试记录。

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
- Android App；
- 真实 Android IME、触摸、鼠标和剪贴板；
- Agent kill -9 与长时间压力/慢消费者测试；
- 一次性 enrollment、身份签名握手、前向保密、重放保护与客户端撤销；
- 15 秒断线 grace period 和 terminal stream resume；
- systemd user/launchd 安装器；
- 多个 named/explicit tmux endpoint。

当前结果证明了 stock tmux + PTY + 出站 Relay + Rust Client 的核心技术路径，不代表生产安全验收完成。
