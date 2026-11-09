# 原生客户端接口契约

iOS 与 Android 客户端只依赖下面这些接口和字段。改动这些字段时同步更新 `contract/fixtures/` 下的样例；两端单测都会解码这些样例，字段被改名或删掉会同时在两端失败。

服务端地址默认为公共服务器 `https://music.op.wiki`，用户可在设置里改为自托管地址；除此之外客户端不内置其他部署地址。相对 URL（`/api/v1/...`）按服务端地址解析；绝对 URL（媒体域）原样使用。

## 用到的接口

| 用途 | 请求 | 响应样例 |
| --- | --- | --- |
| 连接检查 | `GET /health` | `{"status":"ok","service":"..."}` |
| 平台列表 | `GET /api/v1/platforms` | `platforms.json` |
| 搜索 | `GET /api/v1/search?platform=&q=&limit=` | `search.json` |
| 首页推荐 | `GET /api/v1/recommendations/home?platform=applemusic&limit=40[&refresh=true][&exclude=id,id]` | `recommendation-home.json` |
| 自动续播 | `POST /api/v1/recommendations/continue`，JSON `{platform, seed_id, seed_ids, station?, exclude, limit}` | `continuation.json` |
| 歌单／专辑 | `GET /api/v1/collections/resolve?platform=&url=` | `collection.json` |
| 歌词 | `GET /api/v1/lyrics?platform=&track_id=&title=&artist=&album=&duration=<秒>` | `lyrics-*.json` |
| 创建播放任务 | `POST /api/v1/playbacks`，JSON `{platform, query, selected_id, quality, include_cover:false}` | `playback-streaming.json` |
| 等待任务状态 | `GET /api/v1/playbacks/:id?since=<当前状态>&wait_ms=15000` | `playback-*.json` |
| 音频 | 任务里的 `audio_url` | 音频字节，支持 Range |

原生客户端**不传** `streaming_mode`。`wasm_alac` 只属于网页的 WASM 解码器；原生端用系统解码器（AVFoundation／MediaCodec）。

## 账号与同步

服务端开了账号功能时才用到（`/api/v1/auth/config` 返回 `{"enabled":bool,"turnstile_site_key":""}`；站点密钥非空表示注册需要人机验证，原生端此时把「注册」改为打开网页）。会话令牌在 `Set-Cookie: mhub_session=…`；带 Cookie 的写请求必须同时带 `x-mhub-client: 1`。

| 用途 | 请求 | 响应样例 |
| --- | --- | --- |
| 当前用户 | `GET /api/v1/auth/me` | `auth-me.json`（未登录时 `{"user":null}`） |
| 注册 | `POST /api/v1/auth/register`，JSON `{username, password, device_id, device_name}` | `auth-register.json`（含 8 个 `recovery_codes`） |
| 密码登录 | `POST /api/v1/auth/password/login`，JSON `{username, password, device_id, device_name}` | `{"user":{"id","username"}}`，401 为用户名或密码错误 |
| 恢复码登录 | `POST /api/v1/auth/recovery/login`，JSON `{username, code, device_id, device_name}` | 同上 |
| 退出 | `POST /api/v1/auth/logout`，`{}` | `{"ok":true}` |
| 歌单快照 | `GET /api/v1/sync/library/json` | `library-snapshot.json` |
| 歌单操作 | `POST /api/v1/sync/library/json`，JSON `{"ops":[…]}`（一次最多 500 个） | 新快照，同上 |

快照与网页 `readLibrary()` 同形：歌单按创建时间新到旧，每首歌是 `{key, platform, source_id, title, artist, cover}`（`key` 即曲目身份）。时间戳为毫秒。操作：

```json
{"op":"create","id":"<uuid>","name":"…","source_url":"","source_platform":"","tracks":[{platform,source_id,title,artist,cover}]}
{"op":"append","id":"…","tracks":[…]}
{"op":"rename","id":"…","name":"…"}
{"op":"delete","id":"…"}
{"op":"remove","id":"…","key":"platform:source_id"}
{"op":"move","id":"…","key":"…","after":"platform:source_id"|null}
```

## 下载与更新

| 用途 | 请求 |
| --- | --- |
| 下载音频 | 播放任务 `completed` 后 `GET /api/v1/playbacks/:id/audio`（完整文件，`Content-Disposition` 带文件名） |
| 检查更新 | `GET https://api.github.com/repos/jizizr/mhub-<ios|android>/releases?per_page=10`，用到 `tag_name`、`name`、`body`、`html_url`、`draft`、`prerelease`、`assets[].name`、`assets[].browser_download_url`（样例 `github-releases.json`） |

## 播放任务何时可播

两端用同一条规则（`PlaybackReadiness`），由 `playback-*.json` 样例覆盖：

| 任务状态 | 处理 |
| --- | --- |
| `failed` | 报错，显示 `error`；`error_code` 为 `unplayable` 时按「无法播放」跳过（见 design.md「播放器」） |
| `completed` | 播放 `audio_url` |
| `streaming` 且有 `stream_length` | 播放 `audio_url`：渐进源边下边播，服务端按声明长度应答 Range |
| `streaming` 但没有 `stream_length` | 继续等待：解密重封装中的流没有最终长度，系统播放器无法按 Range 读取 |
| `queued`／`processing` | 用 `since=<状态>&wait_ms=15000` 长轮询，状态变化时立即返回，不做定时轮询 |

`completed` 的 Apple Music 无损任务，`/audio` 提供服务端生成的 FLAC 兼容版本，两端系统解码器都能直接播放。

`stream_length` 是为原生端新增的可选字段，网页不读取它，老服务端不返回它（此时原生端退回「等待完成」，仍然可用）。

## 限流（HTTP 429）

回源平台的请求（缓存未命中的 `POST /playbacks`、搜索、歌词、曲目信息、歌单解析、推荐）按 IP 计额度；状态轮询、`/audio*`、`/cover` 不计。额度 3 秒内能补上的，服务端挂起后放行；否则返回：

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 7

{"detail":"请求过于频繁，请稍后再试"}
```

被拒的请求不扣额度，响应字段和请求参数不变。客户端统一处理（iOS `RateLimit`／Android `RateLimit`，用例 `contract/rate-limit-cases.json`）：

- `Retry-After` 是秒数或 HTTP 日期，换算成毫秒，缺失、无法解析或小于 1 秒时按 1 秒算。
- 累计等待（已等的加上这次要等的）不超过调用方给的上限就等待后原样重试，等待可取消；超过上限抛出带 `retryAfterMs` 的限流错误，文案为服务端 `detail` 加「（约 N 秒后可重试）」，N 向上取整。
- 上限：普通请求 10 秒；用户点播的 `POST /playbacks` 2 分钟；预取 0；导入歌单 5 分钟。
- 预取被限流后，下次重试间隔 = max(退避表 [5, 15, 30] 秒中第 attempt 项（超出取最后一项）, Retry-After)。

## 身份与缓存键

- 曲目身份：`platform:source_id`，不含音质。队列去重、歌单、喜欢都用它，与网页 `trackIdentity` 一致。
- 音频缓存键：`platform:source_id:quality`。只有**完整**下载的文件才登记为可离线播放；音质不同即为不同文件，不会拼接。离线时无法得知服务端的 `media_revision`，所以键里不含它；服务端修正某类文件后，清除缓存即可重新下载。
- 歌词缓存键：`platform:source_id`，跨音质复用。
