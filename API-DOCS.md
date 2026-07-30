# ClawBot 接口文档 (v2.0)

## 1. 系统架构

```
微信用户 → WeChatBotService (消息中枢)
             ├── 文本 → 关键词匹配 → 音色/TTS/图片生成/LLM对话
             ├── 图片 → 下载 → DashScope视觉识别 → LLM回复
             ├── 语音 → 下载 → SILK解码 → ASR识别 → 文本路由
             ├── 文件 → 下载 → 文本提取(PDF/DOCX/...) → LLM摘要
             └── 定时提醒 ← @Scheduled 每10秒检查

LLM对话 (LlmService) ← DeepSeek Chat API + Function Calling
  └── 可调用工具: get_weather, create_reminder, create_periodic_reminder,
                   geocode, search_nearby, plan_route, text_to_speech,
                   tarot_reading, search_movies, search_showtimes, purchase_ticket
```

## 2. LLM 工具列表 (Function Calling)

### 2.1 `get_weather` — 天气查询
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | String | ✅ | 城市名称，如 北京、上海、东京 |

### 2.2 `create_reminder` — 一次性提醒
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | String | ✅ | 提醒内容，最长200字符 |
| `trigger_at` | String | ✅ | ISO 8601 时间，如 `2026-07-23T08:00:00+08:00` |
| `reminder_type` | String | ❌ | `text` / `voice` / `both`，默认 text |
| `user_id` | String | ❌ | 用户标识 |

### 2.3 `create_periodic_reminder` — 周期性提醒
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | String | ✅ | 提醒内容 |
| `interval_seconds` | long | ✅ | 间隔秒数，最小60（1分钟）。300=5分钟，3600=1小时，86400=1天 |
| `trigger_at` | String | ❌ | 首次触发时间(ISO 8601)，不填=立即开始 |
| `reminder_type` | String | ❌ | `text` / `voice` / `both` |
| `user_id` | String | ❌ | 用户标识 |

### 2.4 `geocode` — 地址→坐标
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `place` | String | ✅ | 地名，如 北京、杭州西湖、天安门 |

### 2.5 `search_nearby` — 周边搜索
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `location` | String | ✅ | 经纬度 `经度,纬度`，如 `116.473168,39.993015` |
| `keywords` | String | ❌ | 搜索词，如 火锅、咖啡 |
| `types` | String | ❌ | POI类型编码，逗号分隔 |
| `radius` | Integer | ❌ | 半径(米)，默认1000，最大50000 |
| `sortrule` | String | ❌ | `distance`(按距离) / `weight`(按权重) |

### 2.6 `plan_route` — 路线规划
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `location` | String | ✅ | 起点经纬度 `经度,纬度` |
| `keywords` | String | ❌ | 终点关键词搜索 |
| `types` | String | ❌ | 终点POI类型编码 |
| `destination` | String | ❌ | 终点经纬度(直接指定) |
| `radius` | Integer | ❌ | 搜索半径(米) |
| `sortrule` | String | ❌ | 排序规则 |

### 2.7 `text_to_speech` — 文字→语音
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | String | ✅ | 待合成文本 |
| `user_id` | String | ❌ | 用户标识(读取该用户选择的音色) |

### 2.8 `tarot_reading` — 塔罗占卜
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `action` | String | ✅ | `start` / `select_spread` / `draw` / `continue` |
| `user_id` | String | ✅ | 用户标识(会话状态) |
| `user_question` | String | ❌ | 占卜问题 |
| `spread_type` | String | ❌ | `single` / `three_timeline` / `love_triangle` / `decision` / `celtic_cross` |
| `selected_codes` | String | ❌ | 用户选择的牌码，逗号分隔 |
| `continue_choice` | String | ❌ | 是否继续: `yes` / `no` |
| `reveal_cards` | Boolean | ❌ | 是否翻牌 |

### 2.9 `search_movies` — 电影搜索 🆕
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | String | ✅ | 城市名称 |
| `keyword` | String | ❌ | 电影关键词，如 哪吒、科幻 |
| `user_id` | String | ❌ | 用户标识 |

### 2.10 `search_showtimes` — 场次查询 🆕
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `movie_id` | String | ✅ | 电影ID（从 search_movies 获取） |
| `city` | String | ✅ | 城市名称 |
| `date` | String | ❌ | 日期 `2026-08-01`，默认今天 |

### 2.11 `purchase_ticket` — 一键购票 🆕
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `user_message` | String | ✅ | 用户原始消息，含电影名+日期+时间+数量 |
| `user_id` | String | ❌ | 用户标识 |

## 3. 服务层接口

### 3.1 WeChatBotService — 消息中枢
| 方法 | 说明 |
|---|---|
| `init()` | @PostConstruct，异步启动微信登录+消息轮询 |
| `sendDueReminders()` | @Scheduled(10s)，检查并发送到期提醒 |
| `destroy()` | @PreDestroy，关闭微信连接 |

### 3.2 LlmService — LLM对话
| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `chat(userId, userMessage)` | 用户ID, 消息文本 | String | 调用DeepSeek Chat(Function Calling) |
| `chatWithImage(userId, imageBytes, fileName)` | 用户ID, 图片字节, 文件名 | String | 调用DashScope视觉模型识别图片 |

### 3.3 ReminderService — 定时提醒
| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `createReminder(userId, content, triggerAt, type)` | 用户ID, 内容, 时间, 方式 | ReminderTask | 创建一次性提醒 |
| `createPeriodicReminder(userId, content, triggerAt, type, intervalSeconds)` | +间隔秒数 | ReminderTask | 创建周期性提醒 |
| `reschedule(reminderId)` | 提醒ID | void | 周期提醒重新调度 |
| `markSent(reminderId)` | 提醒ID | void | 标记已发送(一次性移除) |

**ReminderTask record:**
```
id, userId, content, triggerAt, type(TEXT/VOICE/BOTH), periodic, intervalSeconds
```

### 3.4 SpeechService — 语音服务
| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `textToSpeech(userId, text)` | 用户ID, 文本 | byte[] | TTS文字→WAV音频 |
| `speechToText(audioBytes, fileName)` | 音频字节, 文件名 | String | ASR语音→文字 |
| `setVoice(userId, voiceName)` | 用户ID, 音色名 | String | 切换音色(15种可选) |
| `getCurrentVoice(userId)` | 用户ID | String | 查询当前音色 |
| `getAvailableVoices()` | — | String | 获取15种音色列表 |

**可用音色：** Cherry(芊悦), Ethan, Emily, Luna, Henry, Chloe, Jasper, Bella, Max, Ruby, Oliver, Stella, Leo, Mia, Oscar

### 3.5 WeatherService — 天气
| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `getWeather(city)` | 城市名 | String | 查询心知天气API，返回格式化天气文本 |

### 3.6 ImageGenerationService — 图片生成
| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `generateImage(prompt)` | 提示词 | byte[] | 调用智谱CogView-3-Plus生成PNG图片 |

### 3.7 FileSummaryService — 文件总结
| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `summarizeFile(fileBytes, fileName)` | 文件字节, 文件名 | String | 提取文本→LLM生成200字中文摘要 |

**支持格式：** PDF / DOCX / XLSX / PPTX / TXT / CSV / LOG

## 4. 数据访问层

### 4.1 ConversationRepository — 会话管理
| 方法 | 说明 |
|---|---|
| `createTable()` | 建表 `conversations(id, user_id, title, created_at, updated_at)` |
| `findByUserId(userId)` | 查询用户会话 |
| `getOrCreate(userId, firstMessage)` | 获取或创建会话(自动用首条消息前30字做标题) |
| `touch(conversationId)` | 更新活跃时间 |

### 4.2 MessageRepository — 消息存储
| 方法 | 说明 |
|---|---|
| `createTable()` | 建表 `messages(id, conversation_id, role, content, message_type, metadata, created_at)` |
| `findRecentByConversationId(conversationId)` | 取最近10条历史(正序) |
| `insert(conversationId, role, content, messageType, metadata)` | 插入一条消息 |

### 4.3 SqliteChatMemory — 聊天记忆
`implements ChatMemory` → 对接 Spring AI 框架

| 方法 | 说明 |
|---|---|
| `add(conversationId, messages)` | 批量保存消息+更新会话时间 |
| `get(conversationId, lastN)` | 读取最近N条消息 |
| `clear(conversationId)` | 清空会话消息 |

## 5. 电影票模块接口 (v1.0 骨架) 🆕

### 5.1 架构
```
MovieTicketTool (LLM工具入口)
  └── MovieTicketOrchestrator (成员7: 指挥家)
        ├── MoviePreferenceParser (成员1: 意图解析)
        ├── MovieSearchClient (成员2: 电影搜索)
        ├── ShowtimeClient (成员3: 场次查询)
        ├── SeatClient (成员4: 座位管理)
        ├── SeatSelector (成员5: 选座算法)
        └── OrderClient (成员6: 下单支付)
```

### 5.2 成员分工

**成员1 — MoviePreferenceParser** (意图解析)
| 方法 | 说明 |
|---|---|
| `parse(userId, userMessage) → MoviePreference` | LLM提取结构化偏好 |

**成员2 — MovieSearchClient** (电影搜索)
| 方法 | 说明 |
|---|---|
| `searchMovies(city, keyword) → List<Movie>` | 搜索上映电影 |
| `getMovieDetail(movieId) → Movie` | 电影详情 |

**成员3 — ShowtimeClient** (场次查询)
| 方法 | 说明 |
|---|---|
| `getShowtimes(movieId, city, date) → List<Showtime>` | 查询放映场次 |
| `getNearbyCinemas(lat, lng, radius) → List<Cinema>` | 附近影院 |

**成员4 — SeatClient** (座位管理)
| 方法 | 说明 |
|---|---|
| `getSeats(showtimeId) → List<Seat>` | 场次座位图 |
| `lockSeats(showtimeId, seatIds) → lockToken` | 锁定座位(300秒超时) |
| `unlockSeats(lockToken) → boolean` | 释放座位 |

**成员5 — SeatSelector** (选座算法)
| 方法 | 说明 |
|---|---|
| `select(allSeats, quantity) → List<Seat>` | 自动最优选座(中心>连座>分散) |

**成员6 — OrderClient** (下单支付)
| 方法 | 说明 |
|---|---|
| `createOrder(showtimeId, seatIds, lockToken, userId) → TicketOrder` | 创建订单 |
| `payOrder(orderId) → TicketOrder` | 支付 |
| `getOrderStatus(orderId) → TicketOrder` | 查状态 |

**成员7 — MovieTicketOrchestrator** (指挥家)
| 方法 | 说明 |
|---|---|
| `searchMovies(userId, city, keyword) → String` | 格式化的电影列表 |
| `searchShowtimes(movieId, city, date) → String` | 格式化的场次列表 |
| `autoPurchase(userId, userMessage) → TicketOrder` | 全流程购票(Step1~8) |
| `requestUserConfirmation(userId, preview) → boolean` | 用户确认交互 |

### 5.3 数据模型
| 类 | 字段 |
|---|---|
| `Movie` | movieId, title, director, cast, rating, posterUrl, releaseDate, duration, genre |
| `Cinema` | cinemaId, name, address, longitude, latitude, distance |
| `Showtime` | showtimeId, movieId, cinemaId, showDate, showTime, hall, originalPrice, price, version |
| `Seat` | seatId, row, column, name, status(AVAILABLE/LOCKED/SOLD), price |
| `MoviePreference` | rawInput, movieKeyword, date, timeRange, quantity, city, locationPreference, minRating |
| `TicketOrder` | orderId, userId, movie, cinema, showtime, seats, totalPrice, status(PAID/...), ticketCode, createdAt |

## 6. 配置参考

```properties
# application.properties 配置项

# DeepSeek LLM
deepseek.api.key=sk-xxx
deepseek.api.base-url=https://api.deepseek.com
deepseek.api.model=deepseek-v4-flash

# DashScope 视觉+TTS+ASR（复用key）
vision.api.key=sk-xxx
vision.api.base-url=https://dashscope.aliyuncs.com/compatible-mode
vision.api.model=qwen3.7-plus

# 心知天气
weather.api.key=xxx

# 高德地图（geocode/search_nearby/plan_route 共用）
amap.api.key=xxx

# 智谱图片生成
image.gen.api.key=xxx
image.gen.api.base-url=https://open.bigmodel.cn/api/paas/v4
image.gen.api.model=cogview-3-plus

# TTS语音
speech.tts.model=qwen3-tts-flash
speech.tts.voice=Cherry

# SILK解码器
silk.decoder.path=D:/SILK/silk-v3-decoder.exe

# SQLite
spring.datasource.url=jdbc:sqlite:clawbot.db
spring.datasource.driver-class-name=org.sqlite.JDBC

# 电影票平台 🆕
movie.platform.provider=mock
movie.platform.base-url=https://api.xxx.com
movie.platform.api-key=xxx
movie.platform.default-city=北京
movie.platform.lock-timeout=300
movie.platform.max-tickets=6
```

## 7. 消息处理流程

```
WeChatBotService.pollMessages() [每2秒轮询]
  │
  ├── 文本消息 → handleMessage()
  │     ├── 匹配"音色"关键词 → handleVoiceCommand() → SpeechService.setVoice()
  │     ├── 匹配"朗读/语音说" → handleTts() → SpeechService.textToSpeech()
  │     ├── 匹配"生成图片/画一个" → handleImageGeneration() → ImageGenerationService
  │     └── 兜底 → LlmService.chat() → DeepSeek Function Calling
  │           ├── LLM可能调用工具 → 工具返回结果 → LLM生成最终回复
  │           └── handleLlmReply() → 检测[audio:...]标记 → 发送语音+文本
  │
  ├── 图片消息 → 下载 → LlmService.chatWithImage() → DashScope视觉识别
  ├── 语音消息 → 下载 → SILK解码 → ASR识别 → 文本路由
  ├── 文件消息 → 下载 → FileSummaryService.summarizeFile() → LLM摘要
  │
  └── 定时提醒 → sendDueReminders() [每10秒]
        ├── 一次性提醒 → 发送 → markSent() 移除
        └── 周期性提醒 → 发送 → reschedule() 更新下次触发时间
```
