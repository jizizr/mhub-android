# MHub for Android

[MHub](https://github.com/jizizr/mhub) 的 Android 客户端（Jetpack Compose + [Glasmio](https://github.com/NEORUAA/Glasmio)，Android 13+）。连接自托管的 MHub 服务端，操作逻辑与网页版、[iOS 版](https://github.com/jizizr/mhub-ios)一致，界面使用液态玻璃标签栏与迷你播放器，参照 Apple Music 的设计语言。

## 安装 Beta

在 [Releases](https://github.com/jizizr/mhub-android/releases) 下载 `MHub-<版本>.apk` 直接安装；之后的版本使用同一签名，可覆盖升级。首次启动填写服务端地址。

## 目录

```text
core/                纯 Kotlin 模块：模型、接口、队列、歌词（与 iOS MHubKit 同一套逻辑）
app/                 Compose + Glasmio 应用：界面、Media3 播放与 SimpleCache 缓存
contract/            接口样例与队列、逐字估算用例 —— 与 mhub-ios 完全相同
docs/                两端共同的交互规范与接口契约 —— 与 mhub-ios 完全相同
```

## 构建与测试

需要 JDK 21 与 Android SDK 37。

```sh
./gradlew :core:test            # 逻辑与共享用例
./gradlew :app:assembleDebug
```

模拟器调试本机后端先 `adb reverse tcp:<端口> tcp:<端口>`；自动化测试播放时用 `-no-audio` 启动模拟器或把媒体音量调到 0。

## 发布

推送 `v*` 标签（如 `v0.1.0-beta.2`）触发 `release.yml`：跑单测、构建签名 Release APK、校验签名并创建 GitHub Release（带后缀的标签为预发布）。签名只来自仓库 Secrets：`MHUB_KEYSTORE_BASE64`、`MHUB_KEYSTORE_PASSWORD`、`MHUB_KEY_ALIAS`、`MHUB_KEY_PASSWORD`；本地构建 Release 时用同名环境变量（`MHUB_KEYSTORE_FILE` 指向 keystore）。密钥库不入库，丢失后用户将无法覆盖升级，务必备份。

## 与 iOS 保持一致

- `contract/` 与 `docs/` 两个仓库必须逐字节相同，CI 的 `shared` 任务会比对 mhub-ios。改动时两边同样提交。
- 改交互或文案先改 `docs/design.md`；改队列行为先在 `contract/queue-cases.json` 加用例；改接口字段先改 `contract/fixtures` 和 `docs/api-contract.md`。Android `core` 与 iOS `MHubKit` 都要通过。
- 原生端不传 `streaming_mode`；服务端返回可选的 `stream_length` 时可边下边播，老服务端则等任务完成后播放。

## 许可

AGPL-3.0-only。通过各平台获取的音乐、封面和歌词，版权归权利人所有。
