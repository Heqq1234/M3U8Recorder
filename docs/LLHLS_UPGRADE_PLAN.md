# M3U8 Recorder LL-HLS / CMAF 升级改造文档

> 创建日期: 2026-05-25
> 状态: 规划中

---

## 一、背景

### 传统 HLS 架构

```
m3u8
  ↓
下载 ts
  ↓
concat merge
  ↓
mp4
```

**适用场景：**
- 传统 HLS
- MPEG-TS 分片
- 单轨音视频

### 现代直播平台升级

现代直播平台（Chaturbate, Twitch, Kick, Apple LL-HLS, 部分 TikTok/CDN）已经升级到：

- **LL-HLS**（Low Latency HLS）
- **CMAF**
- **fMP4 Fragment**
- **分离音视频轨道**
- **Partial Segment**

### 原有 TS 下载架构问题

- ❌ 无法录制
- ❌ 音画不同步
- ❌ FFmpeg concat 失败
- ❌ 文件损坏
- ❌ 无音频
- ❌ Seek 失败
- ❌ 延迟过高

---

## 二、LL-HLS 核心结构

### 1. Master Playlist

```m3u8
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=...
video.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",URI="audio.m3u8"
```

Master Playlist 只负责：
- 视频轨
- 音频轨
- 多码率轨道

### 2. Video Playlist

```m3u8
#EXT-X-MAP:URI="init.mp4"
#EXT-X-PART:DURATION=0.333,URI="part1.m4s"
#EXT-X-PART:DURATION=0.333,URI="part2.m4s"
#EXTINF:2.0,
segment1.m4s
```

### 3. Audio Playlist

```m3u8
#EXT-X-MAP:URI="audio_init.mp4"
#EXT-X-PART:DURATION=0.333,URI="audio_part1.m4s"
```

---

## 三、架构升级目标

### 当前架构

```
m3u8
 ↓
ffmpeg copy ts
 ↓
ts merge
 ↓
mp4
```

### 新架构

```
master.m3u8
 ↓
解析 audio/video playlist
 ↓
下载 init.mp4
 ↓
实时拉取 m4s fragment
 ↓
append fragmented mp4
 ↓
audio/video mux
 ↓
final.mp4
```

### 核心认知转变

| 传统 HLS | LL-HLS |
|----------|--------|
| 文件下载 | 媒体流重组 |

---

## 四、文件级修改清单

### 1. DownloadTask.kt

**路径：** `model/DownloadTask.kt`

**需要新增字段：**

```kotlin
val isLLHls: Boolean = false
val masterPlaylistUrl: String? = null

val videoPlaylistUrl: String? = null
val audioPlaylistUrl: String? = null

val videoInitSegment: String? = null
val audioInitSegment: String? = null

val downloadedFragments: MutableSet<String> = mutableSetOf()

val lastMediaSequence: Long = 0L
val lastPartIndex: Int = 0

val hasSeparateAudio: Boolean = false
val useCmafPipeline: Boolean = false
```

**实现要求：**
- 支持 LL-HLS 标记
- 支持双轨状态管理
- 支持 fragment 去重
- 支持断点恢复
- 支持 media sequence 保存

---

### 2. DownloadManager.kt

**路径：** `manager/DownloadManager.kt`

**需要修改：**

#### 2.1 增加 LL-HLS 检测

```kotlin
fun detectLLHls(content: String): Boolean {
    return content.contains("#EXT-X-PART") ||
           content.contains("#EXT-X-SERVER-CONTROL") ||
           content.contains("#EXT-X-MAP")
}
```

#### 2.2 增加录制模式分流

```kotlin
if (task.isLLHls) {
    startLLHlsRecording(task)
} else {
    startTraditionalDownload(task)
}
```

#### 2.3 增加双轨状态同步

- audio progress
- video progress
- mux progress

#### 2.4 增加恢复逻辑

保存：
- lastMediaSequence
- lastPart
- fragment index

---

### 3. LiveStreamRecorder.kt

**路径：** `record/LiveStreamRecorder.kt`

**当前问题：**
- TS 容器不适配 CMAF
- timeline 不连续
- copy 模式无法修正 PTS
- LL-HLS 卡顿

**Phase 1 修改（优先级最高）：**

放弃 TS 输出，改用 MP4：

```bash
ffmpeg \
  -i live.m3u8 \
  -fflags +genpts \
  -avoid_negative_ts make_zero \
  -max_interleave_delta 0 \
  -c:v copy \
  -c:a aac \
  -movflags +faststart \
  output.mp4
```

**实现要求：**
- 时间线修正
- 音频重新编码
- MP4 输出
- 避免 timestamp drift

---

### 4. M3U8Downloader.kt

**路径：** `downloader/M3U8Downloader.kt`

**需要大改：**

#### 4.1 增加 Master Playlist 解析

```kotlin
fun parseMasterPlaylist(): Pair<String?, String?> {
    // 解析 #EXT-X-STREAM-INF 和 #EXT-X-MEDIA
    // 返回 video playlist 和 audio playlist
}
```

#### 4.2 增加 EXT-X-MAP 支持

```kotlin
// 解析 #EXT-X-MAP:URI="init.mp4"
// 必须先下载 init.mp4
```

#### 4.3 增加 PART 支持

```kotlin
// 解析 #EXT-X-PART
```

#### 4.4 增加 Fragment 去重

```kotlin
val downloadedFragments = ConcurrentHashMap<String, Boolean>()
```

#### 4.5 放弃 TS 文件模式

删除：
```
1.ts, 2.ts, 3.ts
```

改为：
```
stream.mp4 append
```

---

### 5. FFmpegStreamMerger.kt

**路径：** `merger/FFmpegStreamMerger.kt`

**新职责：** Audio/Video Muxer

**实现：**

```bash
ffmpeg \
  -i video_stream.mp4 \
  -i audio_stream.mp4 \
  -c copy \
  final.mp4
```

---

### 6. LiveRecordingService.kt

**路径：** `service/LiveRecordingService.kt`

**可以保留 80%。**

**需要新增：**

#### 6.1 Fragment Pipeline 状态管理

- Audio pipeline status
- Video pipeline status
- Mux status

#### 6.2 增加恢复状态

服务重启后恢复：
- media sequence
- fragment index
- append file

---

### 7. MediaStoreSaver.kt

**路径：** `utils/MediaStoreSaver.kt`

**需要增强：**

支持临时 fragmented mp4：
- `video_stream.mp4`
- `audio_stream.mp4`

不要提前扫描媒体库。

---

### 8. Mp4OutputHelper.kt

**路径：** `utils/Mp4OutputHelper.kt`

**需要新增：**

```kotlin
fun getVideoStreamFile(taskId: String): File
fun getAudioStreamFile(taskId: String): File
fun getFinalMuxFile(taskId: String): File
```

---

## 五、新增文件清单

### 1. MasterPlaylistParser.kt

**职责：**
- 解析 master.m3u8
- 获取 audio/video playlist

### 2. VariantPlaylistTracker.kt

**职责：**
- 实时刷新 LL-HLS playlist
- 跟踪 live edge
- 跟踪 media sequence

### 3. CmafAppender.kt

**职责：**
- init.mp4 + m4s append
- 生成 fragmented mp4

**核心逻辑：**

```kotlin
FileOutputStream(file, true).use { it.write(bytes) }
```

### 4. FragmentIndex.kt

**职责：**
- fragment 去重
- sequence tracking

### 5. LiveEdgeTracker.kt

**职责：**
- 跟踪直播边缘
- 控制刷新频率

### 6. TimestampNormalizer.kt

**职责：**
- 时间戳校正
- discontinuity 修复
- drift 修复

---

## 六、推荐文件结构

```
llhls/
 ├── LLHlsRecorder.kt
 ├── MasterPlaylistParser.kt
 ├── VariantPlaylistTracker.kt
 ├── CmafAppender.kt
 ├── PartDownloader.kt
 ├── FragmentIndex.kt
 ├── StreamMuxer.kt
 ├── LiveEdgeTracker.kt
 └── RecoveryManager.kt
```

---

## 七、改造顺序（重要）

### Phase 1（优先级最高）⭐

**目标：** 修复 FFmpeg timeline

**修改：** `LiveStreamRecorder.kt`

**效果：**
- ✅ 消除卡顿
- ✅ 修复 timestamp drift
- ✅ MP4 输出

---

### Phase 2

**目标：** 增加 Master Playlist 支持

**修改：** `M3U8Downloader.kt`

**效果：**
- ✅ 支持 audio/video split

---

### Phase 3

**目标：** 增加 Fragment Pipeline

**新增：** `CmafAppender.kt`

**效果：**
- ✅ append m4s
- ✅ fragmented mp4

---

### Phase 4

**目标：** 实现真正 LL-HLS Recorder

**效果：**
- ✅ 去 FFmpeg ingest
- ✅ 自己拉 fragment
- ✅ 完全控制 timeline

---

## 八、LL-HLS 关键特性支持

### 1. EXT-X-MAP

```
#EXT-X-MAP:URI="init.mp4"
```

**作用：**
- 提供 moov
- 提供 track metadata
- 提供 codec 信息

**没有 init.mp4：** m4s 无法独立播放

**流程：**
```
下载 init.mp4 → append m4s
```

### 2. EXT-X-PART

LL-HLS 低延迟核心。

**传统播放器只下载：** `#EXTINF`

**问题：**
- 延迟巨大
- 丢失直播实时性

**必须支持：**
- part fragment
- 普通 segment
- 双模式

### 3. Chunk 去重

LL-HLS Playlist 会重复返回旧 fragment。

```kotlin
ConcurrentHashMap<String, Boolean>()
```

**避免：** 视频损坏

### 4. Live Edge Tracking

**不能：** `Thread.sleep(3000)`

**建议：** 500ms ~ 1000ms 动态刷新

---

## 九、HTTP 层升级

LL-HLS 强依赖：
- HTTP2
- Keep-Alive
- Chunked Transfer

**OkHttp 配置：**

```kotlin
OkHttpClient.Builder()
    .retryOnConnectionFailure(true)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()
```

---

## 十、暂停/恢复逻辑升级

### 旧逻辑

```
停止 ts 下载
```

### 新逻辑

```
停止 fragment 拉取
    ↓
关闭 append stream
    ↓
ffmpeg mux
    ↓
输出 final.mp4
```

---

## 十一、最终项目能力

升级完成后支持：

- ✅ LL-HLS
- ✅ CMAF
- ✅ fMP4 fragment
- ✅ Partial Segment
- ✅ 分离音视频直播
- ✅ 低延迟直播录制
- ✅ 自动恢复
- ✅ Timeline 修正
- ✅ Fragment append
- ✅ 实时 mux
- ✅ 多任务后台录制

**兼容平台：**
- Chaturbate-like stream
- Twitch-like stream
- Apple LL-HLS
- 现代 CDN 直播流

---

## 十二、性能优化建议

### 1. 禁止每个 part 单独存文件

**错误：**
```
1.m4s, 2.m4s, 3.m4s
```

**问题：**
- 文件数爆炸
- IO 爆炸
- MediaStore 爆炸

**正确：**
```
append stream.mp4
```

### 2. 使用 BufferedOutputStream

```kotlin
BufferedOutputStream(FileOutputStream(file, true))
```

### 3. 音视频独立协程

```kotlin
CoroutineScope(Dispatchers.IO)
```

分别处理 audio 和 video。

---

## 十三、FFmpeg 推荐命令

### fMP4 录制

```bash
ffmpeg \
  -i live.m3u8 \
  -fflags +genpts \
  -avoid_negative_ts make_zero \
  -max_interleave_delta 0 \
  -c:v copy \
  -c:a aac \
  -movflags +faststart \
  output.mp4
```

### 音视频合成

```bash
ffmpeg \
  -i video_stream.mp4 \
  -i audio_stream.mp4 \
  -c copy \
  final.mp4
```

**不要：** `concat ts`（CMAF 已经不是 TS）

---

## 十四、未来高级支持

1. **Delta Playlist** - `#EXT-X-SKIP`
2. **Blocking Reload** - `?_HLS_msn=xxx&_HLS_part=yyy`
3. **Preload Hint** - `#EXT-X-PRELOAD-HINT`
4. **Timeline Recovery** - discontinuity 修复