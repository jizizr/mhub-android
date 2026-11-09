# 原生客户端接口契约

iOS 与 Android 客户端只依赖下面这些接口和字段。改动这些字段时同步更新 `contract/fixtures/` 下的样例；两端单测都会解码这些样例，字段被改名或删掉会同时在两端失败。

服务端地址由用户在客户端里填写（自托管），客户端不内置任何部署地址。相对 URL（`/api/v1/...`）按服务端地址解析；绝对 URL（媒体域）原样使用。

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

## 播放任务何时可播

两端用同一条规则（`PlaybackReadiness`），由 `playback-*.json` 样例覆盖：

| 任务状态 | 处理 |
| --- | --- |
| `failed` | 报错，显示 `error` |
| `completed` | 播放 `audio_url` |
| `streaming` 且有 `stream_length` | 播放 `audio_url`：渐进源边下边播，服务端按声明长度应答 Range |
| `streaming` 但没有 `stream_length` | 继续等待：解密重封装中的流没有最终长度，系统播放器无法按 Range 读取 |
| `queued`／`processing` | 用 `since=<状态>&wait_ms=15000` 长轮询，状态变化时立即返回，不做定时轮询 |

`completed` 的 Apple Music 无损任务，`/audio` 提供服务端生成的 FLAC 兼容版本，两端系统解码器都能直接播放。

`stream_length` 是为原生端新增的可选字段，网页不读取它，老服务端不返回它（此时原生端退回「等待完成」，仍然可用）。

## 身份与缓存键

- 曲目身份：`platform:source_id`，不含音质。队列去重、歌单、喜欢都用它，与网页 `trackIdentity` 一致。
- 音频缓存键：`platform:source_id:quality`。只有**完整**下载的文件才登记为可离线播放；音质不同即为不同文件，不会拼接。离线时无法得知服务端的 `media_revision`，所以键里不含它；服务端修正某类文件后，清除缓存即可重新下载。
- 歌词缓存键：`platform:source_id`，跨音质复用。
