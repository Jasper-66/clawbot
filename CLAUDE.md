# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Compile
mvn clean compile

# Run tests
mvn clean test

# Full build (compile + test + package)
mvn clean package

# Run application
mvn spring-boot:run
```

## Environment

- **JDK**: JDK 21 (Microsoft OpenJDK `ms-21.0.12`). Use `JAVA_HOME=C:/Users/30287/.jdks/ms-21.0.12` for CLI builds.
- **Maven**: 3.9.11
- **Spring Boot**: 3.3.5
- **Spring AI**: 1.0.0-M6 (OpenAI starter for DeepSeek/DashScope compatibility)

## Architecture

ClawBot is a WeChat bot that integrates LLM chat, image recognition, TTS/ASR, image generation, weather queries, and file summarization. The entry point is `ClawBotApplication.java` in package `com.example.clawbot`.

### Message flow

```
WeChatBotService (polling loop, message router)
  ├── text → weather? → WeatherService
  ├── text → image gen? → ImageGenerationService
  ├── text → TTS request? → SpeechService.textToSpeech()
  ├── text → other → LlmService.chat()
  ├── image → LlmService.chatWithImage() (vision API)
  ├── voice → SpeechService.speechToText() → route as text
  └── file → FileSummaryService (extract text with PDFBox/POI → LLM summary)
```

`WeChatBotService` uses `wechat-ilink-sdk` (ILinkClient) for WeChat connectivity. It logs in via QR code, then polls for messages every 2 seconds. Message deduplication uses a `ConcurrentHashMap.newKeySet()` of message IDs.

### Services

| Service | Responsibility | External API |
|---|---|---|
| `WeChatBotService` | WeChat login, polling loop, message routing, reply sending | wechat-ilink-sdk |
| `LlmService` | Text chat with conversation history, image recognition via vision model. Uses Spring AI `ChatModel` (OpenAI-compatible) for LLM calls and `FunctionCallback` for tool calling | DeepSeek via Spring AI, DashScope (vision) |
| `SpeechService` | TTS (text→WAV), ASR (voice→text), SILK→PCM→WAV decoding, per-user voice preference | DashScope TTS (qwen3-tts-flash), DashScope ASR (qwen3-asr-flash) |
| `WeatherService` | Current weather for a city | 心知天气 (seniverse.com) |
| `ImageGenerationService` | Text→image, handles OpenAI/DashScope response formats | 智谱 CogView-3-Plus |
| `FileSummaryService` | Extract text from PDF/DOCX/XLSX/PPTX/TXT → LLM summary via Spring AI ChatModel | Apache PDFBox + POI, DeepSeek |

### Function Calling (Tool System)

6 tool classes in `tool/` use `@Tool` annotation on their business methods. `config/ToolFunctionConfig.java` wraps them as `FunctionCallback` beans via `ToolCallbacks.from()`:

| Tool | Function Name | Description |
|---|---|---|
| `WeatherTool` | `get_weather` | Query real-time weather by city |
| `GeocodeTool` | `geocode` | Convert place name to lat/lng via Amap |
| `SearchNearbyTool` | `search_nearby` | Search POIs near a location via Amap |
| `PlanRouteTool` | `plan_route` | Plan driving route via Amap |
| `SearchTool` | `web_search` | Web search via Tavily API |
| `TextToSpeechTool` | `text_to_speech` | TTS via DashScope, returns audio file path |

`LlmService` injects `List<FunctionCallback>` and passes them to `OpenAiChatOptions.toolCallbacks()`. The function calling loop (max 30 rounds) is manually implemented to intercept TTS results for audio file path extraction.

### Exception handling

`exception/` package contains:
- `Result<T>` — unified response wrapper with static `error()` factory
- `BusinessException` — runtime exception carrying a business error code
- `GlobalExceptionHandler` — `@RestControllerAdvice` catching `BusinessException` (warn), `MethodArgumentNotValidException` (400), and generic `Exception` (500)

### Configuration

- `RestTemplateConfig` provides a plain `RestTemplate` bean (no interceptors — auth is set per-request in each service)
- `MultiChatModelConfig` creates two `OpenAiChatModel` beans: `@Primary deepSeekChatModel` (chat+tools) and `dashScopeChatModel` (vision)
- `ToolFunctionConfig` uses `ToolCallbacks.from()` to wrap `@Tool`-annotated methods as `FunctionCallback` beans for Spring AI tool calling
- `application.properties` is gitignored; `application-example.properties` serves as the template with placeholder values for all API keys

## Dependencies

- **spring-boot-starter-web** — Spring MVC + Tomcat
- **spring-boot-starter-data-mongodb** — MongoDB driver
- **spring-boot-starter-data-redis** — Redis for conversation memory
- **mysql-connector-j** — MySQL driver (runtime scope)
- **spring-ai-openai-spring-boot-starter** (1.0.0-M6) — Spring AI with OpenAI compatibility (used for DeepSeek and DashScope)
- **wechat-ilink-sdk** — WeChat ILink bot SDK
- **pdfbox 3.0.4** — PDF text extraction
- **poi-ooxml 5.2.5** — Word/Excel/PPT text extraction
- **lombok** — annotation processing
