# RemoteMux 详细设计

> 状态：0.3（Rust 验证版与 Android MVP 已实现，生产加固待实现）
>
> 目标读者：产品所有者、Android/Rust 开发者、安全评审者
> 目标设计与当前实现之间的差异，以“当前实现状态”一节为准。

## 0. 当前实现状态

已经完成 Rust workspace、Relay、Agent、Rust Client 和原生 Android MVP，以及 macOS tmux 3.6a、OrbStack Ubuntu 24.04/tmux 3.4、Android 12 模拟器的端到端验证。

当前是用来验证产品路径的 Phase 1 实现。**更新（protocol v2，2026-08）：pairing bundle 与 ChaCha20-Poly1305 端到端加密已移除**——业务载荷明文经 Relay 转发、仅由 wss 传输层保护，持有 client token 即可管理所有在线机器。生产目标中的一次性 enrollment、Ed25519/X25519 身份握手、前向保密、严格 sequence/replay window、撤销、SQLite、terminal 断线续传和用户级服务安装仍属于 Phase 2，不能把当前验证版直接视为生产安全版本。

当前默认只管理 stock tmux 的 default endpoint。named/explicit socket endpoint 仍保留在目标设计中，尚未进入验证版。

可执行命令和实测范围见 `README.md` 与 `docs/validation.md`。

## 1. 结论摘要

RemoteMux 是一套面向个人多机器环境的远程终端系统：

- 每台 Mac/Linux 机器上直接运行一个普通用户权限的 Rust Agent；
- Agent 只向公网 Relay 发起 `WSS/443` 出站连接；
- Android App 只连接 Relay，统一展示所有机器和 tmux 会话；
- 手机打开某个会话时，Agent 才创建一个 PTY，并以普通 tmux client 的方式附着；
- Terminal 的输入输出在手机与 Agent 之间端到端加密（Phase 2 目标；protocol v2 的当前实现无此层）；
- Relay 只负责在线注册和密文转发，不保存、解析或渲染终端内容；
- 不 fork、不 patch、不替换 tmux，也不修改任何 tmux 配置。

最关键的实现选择是：

> 完整终端画面使用“PTY + `tmux attach-session`”传输，不使用 tmux Control Mode 渲染。

原因是 Control Mode 更适合结构化管理，但不能完整承载 tmux 自己生成的 copy/choose 等界面。普通 PTY attach 才与本地打开一个 terminal 后连接 tmux 的行为一致，因而最适合 Codex、Claude Code、vim、htop 等完整 TUI。

## 2. 目标与非目标

### 2.1 必须实现

1. 一台 Android 手机统一管理大量 Mac/Linux 主机。
2. 主机不需要公网 IP，不开放入站端口。
3. 不创建 TUN/TAP 虚拟网卡，不修改路由、DNS、系统代理或防火墙规则。
4. Agent 可以作为一个独立二进制直接运行。
5. Agent 以当前普通用户身份访问该用户的 tmux。
6. 展示机器在线状态、tmux server、session、window 和 pane 信息。
7. 手机可附着现有 tmux session，获得完整 ANSI/VT 终端输入输出。
8. 支持 Codex CLI、Claude Code 等全屏/半屏交互式 TUI。
9. 支持 UTF-8、中文/IME、Emoji、256 色、True Color、鼠标、粘贴以及 Ctrl/Alt/Esc/Tab/方向键。
10. 移动网络切换和短暂断网后可恢复。
11. Relay 无法读取 terminal 明文。
12. Agent 或网络断开不能终止 tmux server 或里面运行的程序。

### 2.2 明确不做

第一版不做以下事情：

- 不实现远程桌面；
- 不实现 VPN 或任意 TCP 端口转发；
- 不替代 SSH 服务；
- 不替代 tmux server；
- 不自动修改 `.tmux.conf`；
- 不给 Agent root 权限；
- 不提供不受限制的远程 `exec(command)` API；
- 不在 Relay 记录 terminal 内容；
- 不承诺 Windows 原生 tmux，Windows 通过 WSL 使用；
- 不自动安装或升级 tmux、Codex、Claude Code。

## 3. “零改动 tmux”硬约束

这是产品不可回退的设计约束。

Agent 永远不会：

- 写入或读取后再重写 `~/.tmux.conf`；
- 执行 `source-file`；
- 执行 `set-option -g`、`set-window-option -g` 等全局变更；
- 注入 tmux hook；
- 修改 prefix、key binding、status line、theme、mouse、terminal override；
- 替换系统里的 `tmux` 二进制；
- 直接读写 tmux socket 的私有协议；
- 因手机断线而执行 `kill-session` 或 `kill-server`；
- 在未得到用户明确操作时创建新 session/window/pane。

Agent 只允许执行以下三类操作：

1. **查询**：使用 tmux 官方 CLI 和固定 `-F` 格式列出 server/session/window/pane/client；
2. **附着**：在独立 PTY 中执行 `tmux attach-session`；
3. **显式管理**：只有 App 中用户明确点击创建、重命名、关闭时，才执行对应的精确 tmux 子命令。

所有命令均使用 argv 方式调用，不经过 shell 拼接。App 传来的名字不会拼入 shell 字符串；能使用 `$session_id`、`@window_id`、`%pane_id` 时优先使用内部 ID。

## 4. 总体架构

```text
┌──────────────────────── Android App ─────────────────────────┐
│  Machine List → tmux Sessions → Terminal Tabs → Extra Keys   │
│  Android Keystore: phone identity + paired machine identities │
└────────────────────────────┬──────────────────────────────────┘
                             │ TLS/WSS 443
                             │ E2EE ciphertext
                             ▼
┌──────────────────────── Public Relay ────────────────────────┐
│  Authentication / Presence / Routing / Backpressure          │
│  Does not have terminal session keys                         │
│  Does not store terminal input/output                        │
└───────────────┬──────────────────┬──────────────────┬─────────┘
                │ WSS 443 outbound │                  │
                ▼                  ▼                  ▼
       ┌──── Agent A ────┐ ┌──── Agent B ────┐ ┌──── Agent N ────┐
       │ ordinary user   │ │ ordinary user   │ │ ordinary user   │
       │ tmux discovery  │ │ tmux discovery  │ │ tmux discovery  │
       │ on-demand PTY   │ │ on-demand PTY   │ │ on-demand PTY   │
       └───────┬─────────┘ └───────┬─────────┘ └───────┬─────────┘
               ▼                   ▼                   ▼
          stock tmux          stock tmux          stock tmux
```

### 4.1 Relay

Rust 常驻服务，部署在一台有域名和 HTTPS 的 VPS 上。

职责：

- 认证手机和 Agent；
- 启动时根据显式 public URL 输出一行 Android Quick Connect 配置；
- 发放并消费一次性 Agent enrollment token；
- 维护机器在线状态和最后心跳时间；
- 将 App 的密文帧路由到目标 Agent；
- 将 Agent 的密文帧路由回指定 App 连接；
- 限制帧大小、连接数、队列长度和速率；
- 提供 `/healthz`、只含元数据的管理接口；
- 在慢客户端或异常客户端影响 Agent 前主动断开。

Relay 不承担：

- PTY；
- ANSI 解析；
- tmux 管理；
- terminal 内容存储；
- terminal 内容搜索；
- 会话录屏；
- 端到端密钥托管。

当前验证版的 Quick Connect 形如
`REMUX_APP_CONFIG=wss://relay.example.com/~<base64url-client-token>`。编码只是为了让 token
成为安全的单段 URL 文本，不提供保密性；启动 stdout 必须按凭证处理。它只配置 Relay
地址和 Client token，不能承载 Relay 不应知道的逐机器 pairing secret。

### 4.2 Agent

一个 Rust 二进制，目标平台：

- macOS Apple Silicon；
- macOS Intel；
- Linux x86_64；
- Linux aarch64；
- Windows WSL（使用 Linux 构建）。

运行方式：

```bash
remux config
```

`remux config` 交互收集 Relay URL 和 enrollment/token 信息，并生成 TOML 配置与 pairing bundle；也支持 flags/env 的非交互配置。后续只需：

```bash
remux run
```

Agent 默认前台运行；用户需要长期在线时，可显式执行：

```bash
remux install-user-service
```

该命令只安装当前用户的 launchd agent 或 systemd user service，不使用 sudo，不创建系统级 daemon。

职责：

- 主动连接 Relay 并保持心跳；
- 生成和保存机器身份；
- 管理已配对手机的公钥和撤销列表；
- 查询允许的 tmux endpoint；
- 按需创建 PTY attach client；
- 在 PTY 与加密 stream 之间做字节转发；
- 处理 resize、input、detach、重连和流量控制；
- 对所有来自 App 的控制操作执行严格 allowlist 校验。

### 4.3 Rust Client（第一阶段验证端）

在 Android App 之前先实现完整的 Rust Client。它不是一次性测试脚本，而是与未来 Android App 共用协议的参考客户端，并提供同等的核心管理能力：

- 连接 Relay 并列出所有在线机器；
- 与指定 Agent 配对并建立 E2EE 会话；
- 列出 tmux endpoint、session、window 和 pane；
- 创建、重命名和关闭 session；
- 创建、重命名和关闭 window；
- 附着 session，进入本地 raw terminal 模式；
- 转发 stdin/stdout、terminal resize 和信号；
- detach 后恢复本地 terminal 属性；
- 提供脚本友好的非交互子命令，便于自动化测试。

交互示例：

```bash
remux-client machines
remux-client sessions <machine-id>
remux-client session create <machine-id> --name codex --cwd /path/to/repo
remux-client session rename <machine-id> <session-id> codex-main
remux-client attach <machine-id> <session-id>
remux-client session kill <machine-id> <session-id> --confirm
```

`attach` 进入 raw mode 后，本地 Rust Client 表现为一个普通 terminal。默认 detach escape 暂定为 `Ctrl+\` 后接 `d`，该序列由 Client 自己消费，不发送给 tmux；其他字节全部原样转发，包括用户自己的 tmux prefix。

### 4.4 Android App

原生 Android Studio 项目，Kotlin + Jetpack Compose。

主要界面：

1. Relay 登录/配置；
2. 机器列表和在线状态；
3. 机器详情与 tmux endpoint；
4. session/window/pane 列表；
5. 多标签 terminal；
6. 额外按键栏；
7. 配对设备和密钥撤销；
8. 网络与终端诊断。

终端渲染优先采用随 APK 本地打包的 xterm.js，并通过 Android WebViewAssetLoader 加载，不引用公网 CDN。它比自行实现 VT 状态机风险低，也更容易覆盖 IME、CJK、鼠标和复杂 escape sequence。

## 5. tmux 集成设计

### 5.1 进程与权限边界

Agent 只看得到运行它的 OS 用户有权访问的 tmux socket。

- 一个 OS 用户运行一个 Agent；
- 多用户机器如果确实需要管理多个用户，各用户分别运行 Agent；
- 不通过 root Agent 跨用户访问；
- 不使用 `sudo -u`；
- 手机控制 terminal 的权限等价于该 OS 用户本人，因此配对手机必须视为高权限凭证。

### 5.2 tmux endpoint

一个用户可能存在多个 tmux server：

- 默认 socket；
- `tmux -L <name>` 创建的 named socket；
- `tmux -S <path>` 创建的显式 socket。

Agent 配置模型：

```toml
[[tmux.endpoints]]
id = "default"
kind = "default"

[[tmux.endpoints]]
id = "work"
kind = "named"
value = "work"

[[tmux.endpoints]]
id = "special"
kind = "socket"
value = "/safe/explicit/path/tmux.sock"
```

默认只访问 default endpoint。为避免越权和意外暴露，Agent 不默认扫描 `/tmp` 中所有 tmux socket；额外 endpoint 由用户显式添加。

### 5.3 发现会话

使用固定格式，不解析面向人的默认文本：

```bash
tmux list-sessions -F \
  '#{session_id}\t#{session_name}\t#{session_windows}\t#{session_attached}\t#{session_created}\t#{session_activity}'
```

窗口：

```bash
tmux list-windows -t '$session_id' -F \
  '#{window_id}\t#{window_index}\t#{window_name}\t#{window_active}\t#{window_panes}\t#{window_layout}'
```

Pane：

```bash
tmux list-panes -t '@window_id' -F \
  '#{pane_id}\t#{pane_index}\t#{pane_active}\t#{pane_current_command}\t#{pane_current_path}\t#{pane_width}\t#{pane_height}'
```

实际实现不会启动 shell，上述命令仅用于说明 argv 内容。

如果 tmux server 不存在，`list-sessions` 的失败会被映射为“当前无运行中的 tmux server”，Agent 不会为了展示列表而自动启动 tmux。

### 5.4 打开完整终端

App 打开 session 时，Agent：

1. 创建指定 cols/rows 的 PTY；
2. 设置外层 terminal 为 `TERM=xterm-256color`；
3. 保留用户的 locale；
4. 从 attach 子进程环境中移除 `TMUX` 和 `TMUX_PANE`，避免 Agent 本身恰好从 tmux 内启动时出现嵌套 tmux 拒绝；
5. 直接 exec：

```text
tmux -u attach-session -t $session_id
```

没有 shell 插值。

远程关闭或 Agent 断线时，只终止这一条 `tmux attach-session` 客户端进程。tmux server、session、window、pane 和其中的 Codex/Claude Code 不会被关闭。

### 5.5 为什么不使用 Control Mode 传画面

Control Mode 很适合：

- 列出结构；
- 接收 window/pane 变化事件；
- 构建类似 iTerm2 的自定义 pane UI。

但它不是完整 tmux client 画面：tmux 自己生成的 copy mode、choose tree、command prompt 等 UI 不会完整作为 pane output 传出。RemoteMux 的目标是“不改变使用方式”，因此核心 terminal 必须使用普通 PTY attach。

未来可以把 Control Mode 用作结构变更通知优化，但不能替代 PTY 数据通道。

### 5.6 多客户端与尺寸策略

tmux session 同时被桌面和手机附着时，手机尺寸可能影响 tmux 的窗口尺寸。RemoteMux 不会通过修改 `window-size` option 解决，因为那会改变 session 配置。

采用以下客户端级策略：

- 如果没有其他 client：正常 attach，手机 resize 会让 TUI 适配手机；
- 如果已有其他 client：默认给远程 client 增加 `ignore-size` client flag，避免手机宽度缩小桌面会话；
- App 明确显示“保持现有 tmux 尺寸”状态；
- 用户可以主动切换为“以手机尺寸接管”，此操作只改变当前远程 client 行为，不写 tmux 配置；
- 对不支持所需 client flag 的旧 tmux，默认拒绝在已有 client 时无提示附着，并让用户明确选择是否允许尺寸变化。

目标最低版本暂定 tmux 3.2；Agent 启动时探测版本与能力，不依赖用户手工配置。

### 5.7 创建和关闭会话

创建和管理 session 是第一版核心能力，不再作为后续可选功能。

管理操作遵循“用户显式动作”原则：

- “新建 session”才调用 `new-session -d`；
- “重命名 session”才调用 `rename-session`；
- “新建 window”才调用 `new-window`；
- “重命名 window”才调用 `rename-window`；
- “关闭”必须二次确认，且明确区分：
  - 关闭 Rust Client/手机 terminal tab：只 detach；
  - 关闭 tmux window：会终止该 window；
  - 关闭 tmux session：会终止该 session。

Rust Client 必须使用 `--confirm` 或交互确认执行破坏性操作。未来 Android UI 将“Detach”作为主操作，把破坏性 tmux 操作放入二级菜单。

新建 session 使用精确 argv 调用：

```text
tmux new-session -d -s <validated-name> [-c <validated-cwd>]
```

Agent 不接受任意启动命令参数。session 创建后得到用户的默认 shell；用户可以 attach 后正常输入 `codex`、`claude` 或其他命令。这样既具备远程创建工作区的能力，又不在管理协议中增加通用远程 exec。

## 6. 网络与连接模型

### 6.1 主机侧网络行为

Agent 只做以下网络操作：

- DNS 查询 Relay 域名；
- 向 Relay 发起 TCP/TLS/WSS 443 出站连接；
- 定期发送小型心跳；
- 在需要时发送端到端加密的 terminal 帧。

Agent 不会：

- 监听 `0.0.0.0` 或局域网地址；
- 创建 TUN/TAP；
- 安装 Network Extension；
- 写 hosts、resolv.conf 或系统 DNS；
- 修改路由表；
- 设置系统 HTTP/SOCKS 代理；
- 请求路由器端口映射；
- 使用 UPnP/NAT-PMP。

### 6.2 Relay 连接

公网只开放：

- `443/tcp`：HTTPS/WSS；
- 可选 `80/tcp`：仅用于 ACME/跳转到 HTTPS。

推荐 Relay 自身监听 loopback，由 Caddy 或同等反向代理终止 TLS。也可以让 Relay 直接终止 Rustls TLS，部署方案在实现阶段二选一。

### 6.3 心跳和在线状态

- Agent 每 20 秒发送心跳；
- Relay 60 秒未收到心跳标记为 offline；
- App 前台时通过一条 WebSocket 获得 presence 推送；
- App 不需要为每台机器建立单独 Relay 连接；
- 机器数量增加主要消耗 Relay 的空闲 WebSocket 数，而不是手机连接数。

## 7. 身份、配对与端到端加密

### 7.1 三层身份

1. **Relay owner 身份**：决定谁能查看机器元数据和发起路由；
2. **Agent enrollment 身份**：决定哪台机器可以注册到该 owner；
3. **手机—Agent E2EE 身份**：决定谁能解密和控制 terminal。

Relay 身份与 E2EE 身份分离。即使 Relay owner token 泄漏，攻击者仍不能解密已配对 Agent 的 terminal；但可以观察元数据或制造拒绝服务，因此仍需尽快撤销。

### 7.2 Agent enrollment

Relay 管理员生成单次、短时 enrollment token：

```bash
remux-relay token create --kind agent --ttl 10m
```

Agent 首次连接消费 token，注册：

- machine UUID；
- 机器显示名；
- OS/架构；
- Agent 版本；
- Agent identity public key。

Relay 只保存 token 的 Argon2id 哈希，不保存明文；单次 token 成功使用后立即失效。

### 7.3 手机配对

手机要真正控制某台机器，必须单独配对。

流程：

1. 在机器终端运行 `remux pair --ttl 5m`；
2. Agent 在内存生成 256-bit 一次性 pairing secret，不写日志；
3. Agent 显示二维码和可复制的 pairing URI；
4. URI 包含 Relay URL、machine ID、Agent identity public key、pairing ID 和 pairing secret；
5. 手机生成自己的持久 Ed25519 identity；
6. 手机发送带 HMAC 证明的 PairRequest；
7. Agent 验证 TTL、HMAC、machine ID 和 pairing ID，原子消费 secret；
8. Agent 保存手机 public key、显示名和授权时间；
9. 手机保存 Agent public key；
10. 双方显示相同的短指纹供用户核对。

Agent 支持：

```bash
remux clients list
remux clients revoke <client-id>
remux clients revoke-all
```

撤销完全由 Agent 本地决定，Relay 无法绕过。

### 7.4 每次连接的会话密钥

每次 App 与 Agent 建立逻辑连接时：

- 双方各生成临时 X25519 key；
- 双方使用已配对的 Ed25519 identity 对握手 transcript 签名；
- 共享密钥由 X25519 ECDH 得到；
- HKDF-SHA256 根据 machine ID、client ID、双方 nonce 和协议版本派生方向独立的 key；
- 数据使用 ChaCha20-Poly1305；
- nonce 由方向独立前缀和 64-bit 单调 sequence 组成；
- sequence 重复、回退或越窗直接关闭逻辑连接。

这样 Relay 不能中间人替换临时公钥，并提供会话级前向保密。

### 7.5 Relay 能看到什么

Relay 可以看到：

- owner ID；
- machine/client 的路由 ID；
- 在线/离线时间；
- OS、Agent 版本等注册元数据；
- 每帧长度、方向和时间。

Relay 看不到：

- tmux session/window/pane 名称；
- terminal 输入输出；
- 当前目录、命令、Codex/Claude 对话；
- 手机—Agent 会话密钥；
- 配对后的私钥。

Relay 可以丢弃、延迟、重放旧密文或让机器离线，但 sequence、签名和 AEAD 会阻止伪造及有效重放。

## 8. 应用层协议

### 8.1 外层路由帧

Relay 只解析固定外层头：

```text
version       u8
kind          u8
flags         u16
machine_id    16 bytes
client_id     16 bytes
stream_id     16 bytes
sequence      u64
payload_len   u32
payload       opaque bytes
```

控制握手有独立 frame kind；terminal 和 tmux 控制载荷在 E2EE 建立后全部进入 `payload`。

限制：

- 单帧最大 64 KiB；
- 大块输出切片；
- 禁止无上限聚合；
- 默认关闭 WebSocket per-message compression，避免密文前压缩引入额外侧信道；
- Relay 的每连接发送队列有硬上限。

### 8.2 加密后的控制消息

```text
ListEndpoints
ListSessions { endpoint_id }
ListWindows { endpoint_id, session_id }
ListPanes { endpoint_id, window_id }
OpenTerminal { endpoint_id, session_id, cols, rows, size_policy }
TerminalInput { stream_id, bytes }
TerminalResize { stream_id, cols, rows }
TerminalSelectWindow { stream_id, window_id }
TerminalDetach { stream_id }
CreateSession { endpoint_id, name, cwd? }
CreateWindow { endpoint_id, session_id, name?, cwd? }
RenameSession { endpoint_id, session_id, new_name }
KillWindow { endpoint_id, window_id, confirmation_nonce }
KillSession { endpoint_id, session_id, confirmation_nonce }
```

Agent 返回：

```text
EndpointSnapshot
SessionSnapshot
WindowSnapshot
PaneSnapshot
TerminalOpened
TerminalOutput { stream_id, sequence, bytes }
TerminalDetached { stream_id, reason, exit_status? }
OperationAccepted
OperationRejected { stable_error_code, message }
```

### 8.3 不提供通用 exec

协议中没有：

```text
Exec { command: String }
```

原因是它会让 Relay/App 协议直接成为任意远程命令执行接口。用户进入 terminal 后当然可以像普通 shell 一样输入命令，但管理面仅暴露严格定义的 tmux 操作。

## 9. Terminal 数据路径

### 9.1 输出

```text
tmux client → PTY master → Agent chunker → AEAD → Relay → App → xterm.js
```

- Agent 不解析 ANSI；
- Relay 不解析 ANSI；
- App 的 xterm.js 负责 VT 状态机；
- 字节按原样传递，不做 UTF-8 行级拆分；
- chunk 可以在任意字节边界切割，xterm.js 可处理流式 escape sequence。

### 9.2 输入

```text
xterm.js onData / extra-key action → raw bytes → AEAD → Agent → PTY master
```

输入包括：

- 普通 UTF-8 文本和 Android IME；
- Enter/Backspace/Tab/Esc；
- Ctrl+A..Z；
- Alt/Meta 组合；
- 方向键、Home、End、PageUp/PageDown、Insert/Delete；
- F1..F12；
- bracketed paste；
- SGR mouse sequence；
- tmux 默认 prefix `Ctrl+B`，不做特殊拦截。

App 额外按键栏只是生成终端字节，不重新解释 tmux key binding，因此用户自己的 tmux 使用方式保持原样。

### 9.3 剪贴板

第一版：

- App 明确点击 Paste 才发送剪贴板内容；
- 检测 bracketed paste mode 时使用对应包裹序列；
- terminal 输出不自动写 Android 剪贴板；
- OSC 52 默认只弹确认，不静默覆盖系统剪贴板。

## 10. 重连、背压与故障行为

### 10.1 短断线

- Relay/App 连接断开后，Agent 对 terminal stream 保留 15 秒 grace period；
- 每个 stream 最多缓存 1 MiB 尚未确认的密文输出；
- App 在 grace period 内以 stream resume token 和最后 sequence 恢复；
- Agent 只重放缺失 sequence。

### 10.2 长断线或慢客户端

如果超过 grace period、缓存达到上限或 Relay 判定客户端长期背压：

1. Agent 终止远程 attach client；
2. tmux 自动 detach 该 client；
3. tmux session 和内部程序继续运行；
4. App 下次重新 attach，tmux 会向新 terminal 绘制完整当前画面。

任何情况下都不丢弃一部分 ANSI 字节后继续同一 terminal stream，因为那会让客户端 VT 状态失真。宁可 detach/re-attach 获取完整重绘。

### 10.3 Agent 断线

- Relay 将机器标记 offline；
- 手机 terminal tab 显示离线但保留机器/session 标识；
- Agent 指数退避重连，最大间隔 30 秒并带 jitter；
- Agent 进程崩溃不会影响 tmux server；
- Agent 重启后重新发现 tmux session。

### 10.4 Relay 重启

第一版 Relay 在线连接表可以驻内存：

- Relay 重启后 Agent/App 自动重连；
- enrollment、owner、撤销等持久数据存 SQLite；
- terminal stream 重建为新的 attach；
- 不尝试在 Relay 持久化 terminal buffer。

## 11. Android 端详细设计

Android 实现以 `docs/android-interaction.md` 作为交互验收规范。重点不是只有“能显示 terminal”，还必须处理历史滚动、TUI mouse mode 冲突、直接 `Ctrl-C`、可锁定 modifier、CJK IME、bracketed paste、字体缩放/resize、多 tab 和网络重连。下面保留技术架构摘要；手势和状态细节以该文档为准。

### 11.1 技术栈

- Kotlin；
- Jetpack Compose + Material 3；
- Coroutines + StateFlow；
- OkHttp WebSocket；
- kotlinx.serialization；
- Android Keystore 保护手机 identity 私钥和 Relay token；
- 本地打包 xterm.js + fit addon；
- WebViewAssetLoader，禁止 arbitrary file URL 访问；
- minSdk 暂定 28。

### 11.2 页面层级

```text
RelayProfileScreen
  └─ MachineListScreen
       └─ MachineDetailScreen
            ├─ EndpointList
            ├─ SessionList
            │    └─ SessionDetail
            │         ├─ WindowList
            │         └─ PaneList
            └─ TerminalWorkspace
                 ├─ TerminalTabs
                 ├─ WindowSwitcher
                 ├─ XtermWebView
                 └─ TerminalKeyboard / System IME
```

### 11.3 多 terminal

- 每个 tab 对应一个独立 remote tmux client stream；
- tab 标题默认是 `machine / session`；
- App 可同时保留多机器 tab；
- 后台 tab 可选择暂停渲染但不能丢数据；
- 达到内存阈值时提示用户 detach，而不是静默杀掉；
- Terminal Workspace 不强制屏幕方向，手机默认按竖屏使用；任一键盘打开时进入聚焦布局并隐藏非输入必需的管理栏；
- 屏幕旋转触发 fit 后发送新的 cols/rows；
- 每个 tab 查询所 attach session 的 window，并可创建后通过精确 client tty 切换；不发送 tmux prefix，也不改变全局配置。

### 11.4 手机交互

点击 terminal 默认打开 App 自有 terminal 键盘；键盘隐藏时不占据 terminal 空间。高频层提供：

```text
Esc  Tab  Ctrl  Alt  tmux-prefix  ^C  Paste  ←  ↑  ↓  →
123/ABC  Fn  中/EN  Space  .  /  Enter
```

- Ctrl/Alt 为可锁定 modifier；
- 自有键盘的字符与 modifier 走同一 encoder，例如 Ctrl+D 精确生成 `0x04`；
- `^C` 直接发送 `0x03`，作为高频中断键，不经过二次确认；
- 支持一键发送 tmux prefix，但默认值仅作为快捷发送 `Ctrl+B`，不假定用户配置；
- Paste 保留 bracketed paste 和多行/大文本确认；
- `中/EN` 与系统 CJK IME 互斥切换，避免自有键盘和系统键盘同时占屏；
- `Fn` 在原键盘高度内切换到 F1–F12、Home/End、PgUp/PgDn、Ins/Del；
- 用户可视化编辑键盘布局属于后续范围；
- terminal 区双指缩放字体；
- 单指滑动默认滚动 xterm buffer；TUI mouse mode 下提供“历史手势/应用手势”显式切换，双指滑动始终可查看本地历史；
- 查看历史时新输出不能强制回到底部，显示未读行数和“回到实时”按钮；
- 横屏进入沉浸式 terminal。

## 12. Agent 本地状态

默认目录：

- macOS：`~/Library/Application Support/RemoteMux/`；
- Linux：遵循 `$XDG_CONFIG_HOME` / `$XDG_STATE_HOME`；
- WSL：遵循 Linux 路径。

文件：

```text
config.toml              # Relay URL、显示名、tmux endpoint
identity.key             # Agent identity 私钥，0600
authorized_clients.json  # 已配对 client 公钥，0600
state.db                 # enrollment 和非敏感状态
logs/                    # 不含 terminal payload
```

私钥文件创建时使用 exclusive create，拒绝权限过宽的现有文件。日志绝不记录：

- Relay token；
- pairing secret；
- 私钥；
- terminal input/output；
- cwd、session name 等加密业务内容。

### 12.1 tmux 二进制定位

Agent 启动时：

1. 优先使用配置里的绝对路径；
2. 否则使用 Agent 启动时的 PATH 查找；
3. 找不到就报告稳定错误，不自动安装；
4. 记录并展示 `tmux -V`；
5. launchd 环境 PATH 不完整时，引导用户执行一次 `remux doctor` 固化绝对路径。

## 13. Relay 持久化模型

第一版 SQLite 表：

```text
owners
  id, display_name, token_hash, created_at, revoked_at

machines
  id, owner_id, display_name, identity_public_key,
  os, arch, agent_version, enrolled_at, revoked_at

enrollment_tokens
  id, owner_id, token_hash, expires_at, consumed_at

client_devices
  id, owner_id, display_name, relay_auth_public_data,
  created_at, revoked_at

audit_metadata
  id, owner_id, actor_id, machine_id, event_kind, created_at
```

`audit_metadata` 只记录 enrollment、pair、revoke、online/offline、管理操作类型和结果，不记录 session 名称、命令或 terminal payload。

## 14. 威胁模型

### 14.1 需要防御

- 互联网扫描和未认证 WebSocket；
- Relay token 猜测；
- enrollment token 重放；
- pairing QR 被重复使用；
- Relay 管理员读取 terminal；
- Relay 篡改临时密钥；
- 旧密文重放；
- 超大帧和慢消费者拖垮 Agent；
- App 参数形成 shell 注入；
- 手机丢失后的访问撤销；
- Agent 以意外 root 身份运行；
- 非法 socket path 访问。

### 14.2 无法防御

- 主机本身已被 root/内核级恶意软件控制；
- 已解锁手机被攻击者完全控制；
- 用户主动在 terminal 中运行危险命令；
- 流量时间和长度分析；
- Relay 丢包或拒绝服务；
- tmux 或目标 TUI 自身漏洞。

### 14.3 安全默认值

- Agent 检测到 UID 0 默认拒绝运行，必须显式危险开关；
- 默认只允许 default tmux endpoint；
- 默认无通用 exec；
- 默认无会话录制；
- 默认破坏性 tmux 操作二次确认；
- 默认 Relay 只接受 TLS；
- 本地开发 `ws://127.0.0.1` 需要显式 dev flag；
- pairing secret 5 分钟、单次使用；
- Agent enrollment token 10 分钟、单次使用；
- 手机和 Agent 都可独立撤销对方。

## 15. 可观测性和诊断

Relay 指标：

- 在线 Agent/App 数；
- frame/byte 数，不按内容分类；
- 队列深度；
- auth/enrollment 失败数；
- reconnect 次数；
- slow-consumer 断开数。

Agent `doctor`：

```text
Relay DNS/TLS/WSS
Agent identity permissions
tmux binary/version
configured endpoints
default server reachability
PTY creation
TERM/locale/terminfo
paired clients
clock skew
```

诊断包默认脱敏，不包含 terminal 内容、密钥、token、session 名或 cwd。

## 16. 部署模型

### 16.1 Relay VPS

提供两种方式：

1. 单个静态 Relay 二进制 + Caddy；
2. Docker Compose：Relay + Caddy + 持久化 volume。

Relay 仅需：

- 一个域名；
- 443/TCP；
- SQLite volume；
- 定期备份 identity/enrollment 元数据。

不需要为每台 Agent 分配公网端口。

### 16.2 大规模扩展

个人使用第一版按单 Relay 节点设计，可轻松承载数百个空闲 Agent。需要多实例时：

- presence/route registry 移到 Redis/NATS；
- WebSocket 连接按 machine ID 做一致性路由；
- SQLite 升级到 PostgreSQL；
- E2EE 协议和 Agent/App 不需要变化。

## 17. 代码仓库规划

```text
remote/
├─ .cargo/config.toml  # musl 构建 aliases
├─ .github/workflows/ # 测试、静态构建与 Release
├─ DESIGN.md
├─ README.md
├─ Cargo.toml
├─ crates/
│  ├─ protocol/       # 外层帧、加密消息、版本协商
│  ├─ crypto/         # identity、pairing、handshake、AEAD
│  ├─ relay/          # WSS、认证、presence、routing、SQLite
│  ├─ agent/          # 出站连接、tmux 查询、PTY stream
│  └─ client/         # Rust 参考客户端、CLI、raw terminal
├─ android/
│  ├─ app/
│  └─ terminal-assets/ # 固定版本的本地 xterm.js
├─ deploy/
│  ├─ docker-compose.yml
│  ├─ Caddyfile
│  ├─ launchd/
│  └─ systemd-user/
└─ docs/
   ├─ deployment.md
   ├─ agent.md
   ├─ android.md
   ├─ security.md
   ├─ protocol.md
   └─ android-interaction.md
```

Linux 正式分发物统一构建为 `x86_64-unknown-linux-musl` 和 `aarch64-unknown-linux-musl` 静态 ELF。构建使用 `cargo-zigbuild`，CI 在打包前检查 ELF program headers 中不存在动态 interpreter。每个架构的压缩包包含 `remux`、`remux-client` 和 `remux-relay`，并生成 SHA-256 校验文件。macOS 受系统框架约束，继续提供对应架构的原生 Mach-O，不声称完全静态链接。

## 18. 实施阶段

### Phase 0：协议和 tmux spike

- 在本机现有 tmux 3.6a 上验证创建、查询、重命名、PTY attach、resize、detach 和关闭；
- 验证 Codex CLI 和 Claude Code 的颜色、IME、alternate screen；
- 验证有/无其他 tmux client 时的尺寸策略；
- 固化协议 test vectors，保证 Rust/Android 加密一致。

### Phase 1：本地端到端 MVP

- 单 Relay；
- 单 Agent；
- 完整 Rust Client；
- 列出和管理 session/window；
- 创建和重命名 session；
- 打开 terminal；
- 输入、输出、resize、detach；
- 显式关闭 session；
- Agent 退出不影响 tmux。

### Phase 2：Rust 端到端加固

- 多 Agent/多机器；
- 配对、撤销和 E2EE test vectors；
- Relay/Agent 重连；
- 真实 Codex CLI 和 Claude Code 验证；
- macOS/Linux 构建与用户级服务；
- Linux x86_64/aarch64 musl 静态分发与 GitHub Release；
- VPS Docker/Caddy 部署。

Phase 0、Phase 1 和 Android MVP 的核心链路已经完成；Phase 2 中的公网部署和生产身份加固仍未完成。

### Phase 3：Android MVP

- Relay profile；
- 机器/session 列表；
- 配对；
- xterm.js terminal；
- 额外按键；
- 多 tab；
- 短断线恢复。

### Phase 4：部署和加固

- VPS Docker/Caddy；
- launchd/systemd user service；
- enrollment/revoke；
- rate limit/backpressure；
- fuzz/property tests；
- Android release build。

### Phase 5：Agent 工作流增强

- Codex/Claude Code wait-state 通知；
- 任务完成通知；
- session 搜索和标签；
- 可选只读观察；
- 文件/diff 查看，但不改变 terminal 核心协议。

## 19. MVP 验收标准

设计进入实现后，第一版必须通过以下验收：

1. Relay 部署在公网 443，Mac Agent 无入站端口仍能上线；
2. Agent 运行前后，路由表、DNS、代理配置无变化；
3. Agent 不创建或修改 `.tmux.conf`；
4. App 能列出至少 20 台模拟机器；
5. App 能列出已有 tmux session/window/pane；
6. Rust Client 能创建、重命名和显式关闭 tmux session；
7. Rust Client 能打开并完整操作 Codex CLI；
8. Rust Client 能打开并完整操作 Claude Code；
9. UTF-8、Emoji、Ctrl/Alt/Esc、粘贴可用；
10. Rust Client 断开后，tmux 内进程继续运行；
11. Agent 被 `kill -9` 后，tmux 内进程继续运行；
12. Relay 重启后，Agent/Client 自动恢复在线；
13. Relay 数据库和日志中找不到 terminal 明文；
14. 未配对 Client 即使拿到 Relay 登录也无法解密 terminal；
15. 被撤销 Client 无法建立新 E2EE 会话；
16. 已有桌面 tmux client 时，默认不会因远程附着而缩小其窗口；
17. `git diff` 和文件扫描确认代码不存在通用远程 exec API。

## 20. 需要评审确认的设计决策

实现前建议确认以下项目：

1. **Agent 权限模型**：坚持“一名 OS 用户一个 Agent”，不做 root 多用户代理；
2. **tmux 行为**：创建、列出、重命名、附着、detach 和关闭 session 都是首期核心能力；
3. **尺寸行为**：已有桌面 client 时默认 `ignore-size`，手机可主动接管；
4. **Relay 隐私**：不保存 terminal 内容，不提供云端搜索或录屏；
5. **配对模型**：每台机器通过一次性二维码授权手机；
6. **客户端顺序**：先完成 Rust Client 全链路验证，再开始 Android；Android 最低版本暂定 Android 9 / API 28；
7. **第一版平台**：macOS + Linux，Windows 仅 WSL；
8. **公网部署**：单 VPS、单域名、443，先不做 Relay 集群；
9. **通用 exec**：协议层明确不提供；
10. **破坏性 tmux 操作**：默认隐藏在二级菜单并二次确认。

## 21. 参考资料

- tmux Control Mode：<https://github.com/tmux/tmux/wiki/Control-Mode>
- tmux 官方手册：<https://man.openbsd.org/tmux.1>
- xterm.js：<https://github.com/xtermjs/xterm.js>
- Teleport 反向 SSH/多机器设计参考：<https://goteleport.com/docs/reference/deployment/networking/>
