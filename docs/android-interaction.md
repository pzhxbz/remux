# Android Terminal 交互规范

本文既是 Android 交互约束，也是 MVP 实现验收基线。目标不是把桌面键盘生硬搬到手机，而是在触摸、软键盘、小屏幕和不稳定网络下仍然能够可靠操作 tmux、Codex、Claude Code、vim 等 TUI。

当前 MVP 已实现机器/session/window/pane 管理、attach 内 window 创建/切换、多 tab、历史滚动、应用手势切换、未读提示、自有 terminal 键盘/系统 IME 切换、bracketed paste、可配置 tmux prefix、竖屏紧凑布局、旋转重绘和 TalkBack 基础支持。OSC 52 授权、键盘布局可视化编辑、stream resume、隐藏系统栏的沉浸模式和完整真机 TalkBack 验收仍在后续范围。

## 1. 核心原则

1. terminal 内容区优先保证字节语义正确，不把普通手势偷偷转换成方向键或命令。
2. Detach 与 Kill 永远分离。关闭 terminal tab 默认只 detach；kill window/session 必须进入二级操作并确认。
3. 用户滚动查看历史时，新输出不能强制把视口拉回底部。
4. Android 系统返回键不发送 `Ctrl-C`、Esc 或 tmux prefix。
5. 所有快捷键最终生成明确的 terminal 字节，不改 tmux key binding。
6. 手势含义必须可见、可切换，避免 TUI mouse mode 与本地历史滚动互相争抢。

## 2. 信息架构

```text
Relay Profiles
  └─ Machines（搜索、收藏、在线状态）
       └─ Machine Detail
            ├─ Sessions
            │    ├─ Create / Rename / Kill
            │    └─ Windows / Panes
            └─ Terminal Workspace
                 ├─ Machine/session breadcrumb
                 ├─ Terminal tabs
                 ├─ New Window / window switcher
                 ├─ Connection/scroll state chips
                 ├─ xterm viewport
                 └─ Terminal keyboard / system IME switch
```

机器数量较多时，机器页必须支持名称搜索、在线/离线筛选、收藏置顶和最近使用排序。机器 ID 只放在详情/诊断中，不作为主要识别信息。

## 3. Terminal 视口状态

Terminal UI 至少维护以下正交状态：

| 状态 | 可见表现 | 输入行为 |
|---|---|---|
| Live | 位于底部，无标记 | 输出自动跟随 |
| History | 顶部显示“距实时 N 行/新输出 N 行” | 输出继续写入，但不抢滚动位置 |
| Selection | 选区手柄和 Copy | 暂停应用鼠标手势，不发送选中文本 |
| Application pointer | 显示“应用手势”chip | 手势编码成远端 mouse/wheel |
| Modifier armed | Ctrl/Alt 等按钮高亮 | 下一键带 modifier 后自动释放 |
| Modifier locked | 按钮使用锁定样式 | 连续按键均带 modifier |
| Reconnecting | 半透明状态条，不清空当前画面 | 暂停输入或显式排队少量安全输入 |
| Offline | 冻结画面，提供 Reattach | 不假装输入已经送达 |

状态变化不能只靠颜色表达；同时使用图标、文本和 TalkBack 描述。

## 4. 上下滑动与历史信息

### 4.1 普通模式

- 单指上下拖动滚动 xterm.js 的本地 scrollback；
- fling 有惯性，但速度上限要避免一次越过大量输出；
- History 浮层提供向前/向后整页翻动和回到 Live，不要求用户只能反复短距离拖动；
- 用户离开底部后进入 History 状态；
- 新输出继续进入 buffer，不改变当前 viewport；
- 右下角显示“回到实时”浮动按钮，并显示尚未查看的新增行数；
- 点击按钮或拖到底部恢复 Live。

每个 terminal tab 默认保留 20,000 行 scrollback，设置中可调整。到达内存上限时从最旧内容开始淘汰并给出一次性提示。默认不把 terminal 历史持久化到磁盘，也不上传 Relay。

### 4.2 TUI mouse mode 冲突

Codex、Claude Code、vim、htop 等程序可能开启 DEC mouse tracking。界面提供两个明确模式：

- **历史手势**（默认）：单指滚动本地 scrollback，不向远端发送 mouse；
- **应用手势**：单指滚动/点击根据 xterm mouse mode 编码为 SGR mouse/wheel 发给远端程序。

状态 chip 可一键切换。无论当前模式如何，双指上下拖动始终作为“紧急本地历史滚动”，确保用户不会被应用 mouse capture 困住。

### 4.3 alternate screen 与 tmux history

alternate screen 本身通常没有普通行式 scrollback。不能承诺把全屏 TUI 的每个中间画面还原成可读历史。需要区分：

- xterm 本地 scrollback：适合 shell/普通输出；
- 应用自身滚动：在“应用手势”模式发送 wheel；
- tmux copy mode：读取 tmux 自己的 pane history，最符合用户原有环境。

terminal 键盘的扩展层后续可提供“tmux copy mode”宏，默认建议为 `Ctrl-b [`，但必须允许用户配置或关闭，因为 RemoteMux 不假设用户的 tmux prefix，也不修改 tmux 配置。

## 5. 键盘与快捷键

### 5.1 App 自有 terminal 键盘

点击 terminal 默认打开 App 自有的紧凑 QWERTY 键盘。它不经过 Android IME 猜测，字符、modifier 和特殊键全部由同一个 terminal encoder 生成确定字节。键盘关闭时整个区域消失，不常驻额外快捷栏。

第一行是可横向滚动的高频 terminal 操作，Paste 位于首屏：

```text
Esc  Tab  Ctrl  Alt  tmux-prefix  ^C  Paste  ←  ↑  ↓  →
```

底行固定保留布局和输入法切换：

```text
123/ABC  Fn  中/EN  Space  .  /  Enter
```

`Fn` 不增加新行，而是把 QWERTY 区原位替换为：

```text
F1…F6
F7…F12
Home  End  PgUp  PgDn  Ins  Del
```

- `^C` 是独立的快速按钮，立即发送单字节 `0x03`；不能触发 Android 的取消逻辑或复制逻辑；
- `Ctrl` 后按自有键盘的 `d` 必须发送单字节 `0x04`，不能依赖系统键盘组合事件；
- `Ctrl`/`Alt` 单击为 one-shot，发送下一键后自动释放；
- 长按 modifier 切换 locked 状态，再次点击释放；
- one-shot 与 locked 使用不同图标，并提供轻/重两级触觉反馈；
- 键盘顺序、显示文字和发送字节/宏的可视化配置仍在后续范围；
- 危险宏不能在默认布局中出现，宏编辑页显示其实际字节预览。

为减少误触，`^C` 与方向键之间保留间距，但不增加二次确认，因为中断前台程序是 terminal 中的高频正常操作。

### 5.2 Android 系统键盘和 IME

- `中/EN` 显式切换到 Gboard 等系统 IME，用于中文、日文、语音输入和厂商输入法能力；
- App 自有键盘与系统 IME 是互斥状态；系统 IME 仍可见时绝不渲染自有键盘；
- 中文、日文等 composition 阶段只在本地候选区显示，`compositionend`/commit 后才把 UTF-8 文本发送给 PTY；
- 系统 IME 负责组合文本，自有键盘负责 Backspace、Enter、Tab、Esc、Ctrl/Alt、方向键等精确 terminal 字节；
- 支持 Gboard 等软键盘和物理蓝牙键盘；
- Android Back：先隐藏当前自有键盘或系统 IME，键盘已隐藏时才返回上一层；绝不隐式发送 Esc/Ctrl-C；
- terminal 获得焦点时禁用系统文本自动更正对命令的改写。

### 5.3 粘贴与剪贴板

- Paste 是显式操作；
- 远端启用 bracketed paste 时添加 `ESC[200~`/`ESC[201~`；
- 多行或超过可配置阈值的粘贴先预览并确认；
- 预览显示不可见控制字符，默认拒绝 NUL；
- 长按选择只复制本地渲染文本，不自动执行；
- OSC 52 默认询问后才写入 Android 剪贴板。

## 6. 触摸手势

- 单击：聚焦 terminal；
- 单指竖向拖动：按当前 History/Application 模式处理；
- 双指竖向拖动：始终滚动本地历史；
- 双指缩放：改变字体大小，完成后 debounce 150–250 ms 再发送 PTY cols/rows；
- 长按：在 History 模式进入文本选择；Application pointer 模式先显示“选择/发送鼠标”选择条；
- terminal 内容区默认不使用横滑切换 tab，以免与选择、鼠标和 TUI 操作冲突；tab 通过顶部 tab bar 切换；
- 屏幕旋转或分屏变化在 fit 完成后发送一次稳定尺寸，避免 resize storm。
- Android attach 使用当前 xterm 网格接管 client 尺寸；键盘、分屏、旋转和字体变化后把去重后的 cols/rows 发送给 PTY。
- renderer 因旋转重建时，App 先 resize，再请求 Agent 对该 attach client 执行 `tmux refresh-client -t <tty>`；不向 pane 发送 `Ctrl-L`，也不修改 tmux 配置。
- Terminal Workspace 不强制改变设备方向，默认按竖屏使用；无 IME 时使用单行紧凑 tab bar，IME 打开时暂时隐藏 tab/window/status 区，并把空间优先留给 xterm。

## 7. Session 与多终端管理

- 机器详情突出“新建 session”和“Attach”，而不是 Kill；
- 新建 session 只填写 name/cwd，明确说明启动默认 shell；
- cwd 由 Agent 在目标机器校验，不提供远程目录任意浏览 API；
- terminal tab 标题为 `machine / session`，离线、重连、后台输出用 badge 表示；
- 已 attach 的 tab 提供 `+ Window` 和按 index 排序的 window chips；创建后只对当前 tmux client 执行 `switch-client`，立即切入新 window；
- 选择 window 只接受当前 attach session 的 `@window_id`，不写 tmux option、不发送 prefix，也不影响其他 tmux client 当前所看的 window；
- 切换 tab 不 detach；显式关闭 tab 才 detach；
- 后台 tab 继续接收和解析输出，渲染可降频但不能丢弃半段 ANSI；
- 达到内存/队列上限时提示 detach/re-attach，不能静默丢字节后继续使用损坏的 VT 状态；
- Kill window/session 使用危险色、显示具体目标，并进行二次确认；session kill 文案说明 Codex/Claude 等进程也会终止。

## 8. 网络切换和重连

- Wi-Fi/蜂窝切换时保留当前 terminal 画面并显示 Reconnecting；
- 未确认送达的按键默认不无限排队，避免重连后意外执行旧命令；
- 短断线恢复成功后显示轻量提示，不弹阻塞对话框；
- stream 无法 resume 时执行新的 attach，让 tmux 完整重绘；
- Agent offline 时保留 tab 和机器/session 标识，提供重试，不自动 kill；
- 对管理操作显示明确的 pending/success/failure，破坏性操作不能因网络重试而重复执行。

## 9. WebView/xterm.js 边界

- xterm.js 和 addon 固定版本并随 APK 本地打包；
- 使用 `WebViewAssetLoader` 的受控 HTTPS origin，不允许公网 CDN；
- 禁止任意 file/content URL、mixed content 和页面导航；
- client token 只存在 Kotlin 层，不注入 JavaScript（protocol v2 已移除 pairing key / AEAD 层）；
- Kotlin 与 terminal 页面通过限定 origin 的 `WebMessagePort` 传输类型化消息；
- JS 只接触已经解密的当前 terminal 字节以及用户输入字节；
- bridge 事件至少包含 output、input、resize、scroll-position、mouse-mode、bracketed-paste、alternate-screen 和 title；
- xterm `write` 使用小批量和 completion callback 做背压，避免大输出卡死主线程。

## 10. 可访问性和人体工学

- 可点目标至少 48 dp；
- 支持系统字体缩放，但 terminal 字体与 UI 字体可分别设置；
- terminal 键盘提供 TalkBack label，例如“发送 Control C”，不能只读作字符；
- 高对比度主题、色盲友好状态、可关闭动画；
- 支持横屏沉浸模式、保持屏幕常亮开关和外接键盘；
- 触觉反馈可独立关闭。

## 11. Android 验收场景

1. 持续输出时上滑，视口保持原位且新增行 badge 正确；
2. 一键返回实时输出；
3. TUI 开启 mouse tracking 时能在历史手势和应用手势间切换；
4. `^C` 按钮精确发送 `0x03` 并中断前台程序；
5. 自有键盘的 Ctrl one-shot/lock、Ctrl-D、Alt/Esc/Tab/方向键发送精确 terminal 字节；
6. `中/EN` 切换系统 IME 时不出现双键盘；中文组合文本只在 commit 后发送且不重复；
7. 多行粘贴使用 bracketed paste 并触发安全预览；
8. pinch/旋转不会造成 resize storm；
9. 查看历史时切换 tab、网络重连都不丢滚动位置；
10. tab close 只 detach，session kill 必须二次确认；
11. 自定义 tmux prefix 可以配置，默认快捷宏不会修改远端配置；
12. TalkBack 能读出所有 terminal 按键和连接状态。
13. 竖屏打开任一键盘后 xterm 仍保持可操作高度，Android Back 先隐藏键盘并恢复完整控制栏；
14. `+ Window` 创建后自动切换，window chips 能在不发送 tmux prefix 的情况下快速切换当前 attach client。
15. `Fn` 层的 F1–F12、Home/End、PgUp/PgDn、Ins/Del 与 xterm 序列一致，切层不改变 terminal 高度。
