# RemoteMux Android

原生 Kotlin/Jetpack Compose 客户端，最低支持 Android 9（API 28）。终端由固定版本的 xterm.js 在受限本地 WebView origin 中渲染，Relay token、机器密钥和 AEAD 操作只存在于 Kotlin 层。

## 本地构建

首次构建需要在 `local.properties` 中设置 Android SDK：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

然后执行：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

debug build 允许 `ws://`，用于本机或局域网验证；release build 默认拒绝明文网络，应使用 `wss://`。
