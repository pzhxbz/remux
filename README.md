# RemoteMux

RemoteMux 是一个面向多机器的远程 tmux 控制原型。每台机器运行一个普通用户权限的 Rust Agent；Agent 只主动连接 Relay。Rust Client 通过同一条 Relay 连接发现机器、管理 tmux session/window/pane，并以 raw terminal 方式附着到完整 tmux UI。

当前完成的是 Android 开发前的 Rust 端到端验证版。Android App 尚未开始。

## 已实现

- Relay 上的 Agent/Client 鉴权、机器在线状态和有界密文转发；
- Client 与 Agent 之间的 ChaCha20-Poly1305 密文载荷，Relay 不持有机器密钥；
- 创建、列出、重命名和显式关闭 tmux session；
- 创建、列出、重命名和显式关闭 tmux window；
- 列出 pane；
- PTY + 原生 `tmux attach-session`，原样传输 ANSI、UTF-8 和按键；
- terminal resize、手动 detach、客户端断开清理；
- 已有 tmux client 时自动使用 `ignore-size`；
- Agent/Relay 断线重连，且不终止 tmux session；
- 所有 tmux 管理命令使用固定 argv，不经过 shell；协议中没有通用远程 exec。

测试过的主要组合：

- Ubuntu 24.04 ARM64 / tmux 3.4 / Rust 1.85.0；
- macOS ARM64 / tmux 3.6a。

详细实测记录见 [docs/validation.md](docs/validation.md)。完整目标架构和安全设计见 [DESIGN.md](DESIGN.md)。

## 当前安全边界

这是验证版，不应直接暴露到生产公网。当前 pairing bundle 中保存每台机器的 256-bit 预共享密钥，业务载荷已端到端加密，但生产设计中的一次性 enrollment、Ed25519/X25519 握手、前向保密、重放窗口、客户端撤销、SQLite 状态和速率限制尚未实现。

本地测试可以使用 `ws://127.0.0.1`。公网使用前必须完成上述加固，并在 Relay 前使用 HTTPS/WSS 反向代理。`agent.toml`、pairing bundle 和 Relay token 都是高权限凭证，不要提交到版本库或发送给第三方。

## 依赖

- Rust 1.85 或更高版本；
- Agent 机器安装 stock tmux；
- macOS 或 Linux。Windows 暂只计划支持 WSL。

Agent 不会安装 tmux，也不会创建、读取后重写或 source `.tmux.conf`。

## 构建

本机开发构建：

```bash
cargo build --workspace
cargo test --workspace
```

生成的程序：

```text
target/debug/remux
target/debug/remux-client
target/debug/remux-relay
```

Linux 分发统一使用 musl 静态链接，支持 x86_64 和 aarch64。安装 Zig 与 `cargo-zigbuild` 后执行：

```bash
cargo install cargo-zigbuild --version 0.20.1 --locked
scripts/build-musl.sh x86_64-unknown-linux-musl
scripts/build-musl.sh aarch64-unknown-linux-musl
```

也可以用 `cargo musl-x86_64` 或 `cargo musl-aarch64`。脚本会检查 ELF 不包含动态 interpreter，并生成 `dist/remux-<target>.tar.gz`。Git tag `v*` 会触发 [.github/workflows/release.yml](.github/workflows/release.yml)，测试 Rust 1.85 MSRV、构建两个静态架构、生成 SHA256SUMS 并上传 GitHub Release；手动触发时生成 Actions artifacts。

本地执行构建后，已验证的产物位于 `dist/`：

- `remux-aarch64-unknown-linux-musl.tar.gz`；
- `remux-x86_64-unknown-linux-musl.tar.gz`；
- `SHA256SUMS`。

`dist/` 是可重建的输出目录，不纳入版本控制；正式发布产物由 GitHub Actions 附加到对应 Release。

## 本地快速验证

以下三个 token/secret 仅作示例，请自行生成高熵值。

1. 启动 Relay：

```bash
remux-relay \
  --listen 127.0.0.1:8787 \
  --agent-token 'replace-with-agent-token' \
  --client-token 'replace-with-client-token'
```

2. 在需要被管理的机器上配置 Agent：

```bash
remux config
remux doctor
remux run
```

`remux config` 会交互询问 Relay URL 和 token，并使用主机名作为默认机器名。自动化配置也可以写成：

```bash
REMUX_AGENT_TOKEN='replace-with-agent-token' remux config \
  --relay ws://127.0.0.1:8787 \
  --name my-linux-host
```

默认 Agent 配置位置为 macOS `~/Library/Application Support/RemoteMux/agent.toml`，Linux `$XDG_CONFIG_HOME/remux/agent.toml` 或 `~/.config/remux/agent.toml`；可用 `--config` 或 `REMUX_CONFIG` 覆盖。`pairing.toml` 默认与 Agent 配置相邻。两个文件在 Unix 上均以 `0600` 创建。把 `pairing.toml` 安全复制到 Client 机器；它等价于该机器的远程终端控制凭证。

3. 使用 Rust Client：

```bash
export REMUX_CLIENT_TOKEN='replace-with-client-token'

remux-client --relay ws://127.0.0.1:8787 machines
remux-client sessions --pairing pairing.toml
remux-client create-session --pairing pairing.toml --name codex --cwd /path/to/repo
remux-client windows --pairing pairing.toml --session-id '$0'
remux-client panes --pairing pairing.toml --window-id '@0'
remux-client attach --pairing pairing.toml --session-id '$0'
```

attach 后，tmux prefix 和其他输入全部原样发送。Rust Client 自己的 detach 组合键为 `Ctrl+\`，松开后再按 `d`。这只关闭临时 attach client，tmux session 继续运行。

重命名和创建 window：

```bash
remux-client rename-session --pairing pairing.toml --session-id '$0' --name codex-main
remux-client create-window --pairing pairing.toml --session-id '$0' --name shell
remux-client rename-window --pairing pairing.toml --window-id '@1' --name logs
```

破坏性操作必须显式确认：

```bash
remux-client kill-window --pairing pairing.toml --window-id '@1' --confirm
remux-client kill-session --pairing pairing.toml --session-id '$0' --confirm
```

所有非交互管理命令都支持全局 `--json`，便于脚本调用。

## 行为保证

- Agent 只访问启动它的 OS 用户能够访问的默认 tmux server；
- Agent 不监听主机入站端口，只发起 Relay WebSocket 出站连接；
- 普通 detach、Client 断开、Relay 断开和 Agent 退出都不调用 `kill-session`；
- 只有收到用户显式的 `KillSession`/`KillWindow` 请求才执行相应 tmux 命令；
- 新 session 只启动用户默认 shell，管理协议不接受任意命令字符串。
