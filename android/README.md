# RemoteMux Android

原生 Kotlin/Jetpack Compose 客户端，最低支持 Android 9（API 28）。终端由固定版本的 xterm.js 在受限本地 WebView origin 中渲染，Relay token 只存在于 Kotlin 层。

## 已实现

- Relay 配置和加密配置存储；
- 多机器搜索、在线筛选、收藏和最近使用排序；
- tmux session/window/pane 管理，所有 kill 操作二次确认；
- attach 内一键新建 window，并通过可横向滚动的 `index:name` 按钮快速切换；
- 最多 6 个保活 terminal tab，返回机器页不会 detach，关闭 tab 只 detach；
- xterm.js 6 本地资源、ANSI/truecolor/UTF-8/CJK/IME、20,000 行 scrollback；
- 惯性滑动历史、整页前后翻动、未读行提示、回到实时、TUI 应用手势切换和双指字体缩放；
- 点击 terminal 打开 App 自有键盘，精确发送 `Ctrl-D`/`Ctrl-C`、Ctrl/Alt、Esc/Tab、方向键和可配置 tmux prefix；
- 固定 `中/EN` 键切换系统 CJK IME，`Fn` 扩展层提供 F1–F12、Home/End、PgUp/PgDn、Ins/Del；两种键盘不会同时显示；
- bracketed paste；多行或超过 200 字符的粘贴先预览确认；
- 竖屏优先的紧凑 terminal；键盘隐藏时不常驻快捷栏，输入时隐藏 window/status 区以保证 xterm 高度；
- attach 后按手机实际网格自动 resize，键盘/旋转变化后通过 stock tmux `refresh-client` 恢复画面，不向 pane 注入按键；
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
2. 在机器页进入目标机器（在线机器自动出现，无需逐机配对），管理或新建 session，然后点 Attach；
3. terminal 顶部可新建/切换 window 以及切换“历史手势/应用手势”；点击 terminal 默认打开 App 自有键盘，`中/EN` 切换系统中文输入法，`Fn` 打开功能键层；系统返回键先收起当前键盘再恢复完整控制栏。

模拟器访问宿主机 Relay 时可使用 `ws://10.0.2.2:<port>`。真实设备应使用可达的 WSS 域名。Quick Connect 行包含 Client token，`~` 后只是 URL-safe 编码而非加密；不要把该行或 Relay token 提交到版本库。持有 Client token 即可管理 Relay 上所有在线机器；终端内容明文经过 Relay（TLS 只保护传输段），Relay 运营者及 TLS 路径上的主动 MITM 可读取终端会话，不可信网络请为 Relay 配置真实证书。

## 当前边界

网络断开后保留最后画面并显示 Reattach，但断线后不恢复原 stream，用户需要显式重新 attach。一次性 enrollment、设备撤销、前向保密和公网部署加固仍属于后续阶段。
