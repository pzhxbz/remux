# RemoteMux Android

原生 Kotlin/Jetpack Compose 客户端，最低支持 Android 9（API 28）。终端由固定版本的 xterm.js 在受限本地 WebView origin 中渲染，Relay token、机器密钥和 AEAD 操作只存在于 Kotlin 层。

## 已实现

- Relay 配置、加密配置存储和 `pairing.toml` 文件导入；
- 多机器搜索、在线筛选、收藏和最近使用排序；
- tmux session/window/pane 管理，所有 kill 操作二次确认；
- attach 内一键新建 window，并通过可横向滚动的 `index:name` 按钮快速切换；
- 最多 6 个保活 terminal tab，返回机器页不会 detach，关闭 tab 只 detach；
- xterm.js 6 本地资源、ANSI/truecolor/UTF-8/CJK/IME、20,000 行 scrollback；
- 上滑历史、未读行提示、回到实时、TUI 应用手势切换和双指字体缩放；
- `Esc`、`Tab`、可配置 tmux prefix、Ctrl/Alt、精确 `Ctrl-C`、方向键、编辑键和 F1–F12；
- bracketed paste；多行或超过 200 字符的粘贴先预览确认；
- 竖屏优先的紧凑 terminal；IME 打开时隐藏导航/window 状态区，保证 xterm 保持可用高度；
- 屏幕旋转后通过 stock tmux `refresh-client` 恢复画面，不向 pane 注入按键；
- 一行 Quick Connect 配置，仍保留手动 Relay URL/token 设置作为后备；
- TalkBack 时启用 xterm screen-reader mode；Debug 可用 `ws://`，Release 强制 `wss://`。

App 不创建或修改 `.tmux.conf`。所有 terminal tab 都是临时 `tmux attach-session` client；App 返回、切换 tab 不 detach，显式关闭、进程结束或网络断开只 detach，均不会 kill session。

## 本地构建

首次构建需要在 `local.properties` 中设置 Android SDK：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

然后执行：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。debug build 允许 `ws://`，用于本机或局域网验证；release manifest 和运行时校验都会拒绝明文网络，必须使用 `wss://`。

## 首次连接

1. 复制 Relay 启动时输出的 `REMUX_APP_CONFIG=ws(s)://host:port/~...`，在 Quick Connect 中整行粘贴；也可以输入 `server:port/secret`，或展开 Manual setup；
2. 用系统文件选择器导入 Agent 生成的 `pairing.toml`；
3. 在机器页进入目标机器，管理或新建 session，然后点 Attach；
4. terminal 顶部可新建/切换 window 以及切换“历史手势/应用手势”；点击 terminal 打开键盘后进入聚焦布局，顶部管理区暂时隐藏，系统返回键先收起键盘再恢复完整控制栏。

模拟器访问宿主机 Relay 时可使用 `ws://10.0.2.2:<port>`。真实设备应使用可达的 WSS 域名。Quick Connect 行包含 Client token，`~` 后只是 URL-safe 编码而非加密；不要把该行、Relay token 或 pairing 文件提交到版本库。pairing 是每台机器独立的 E2EE 控制凭证，不能由 Relay 连接码替代。

## 当前边界

网络断开后保留最后画面并显示 Reattach，但 protocol v1 不恢复原 stream，用户需要显式重新 attach。一次性 enrollment、设备撤销、前向保密和公网部署加固仍属于后续阶段。
