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

- **JDK**: JDK 17 (Microsoft OpenJDK `ms-17.0.19`). Use `JAVA_HOME=C:/Users/30287/.jdks/ms-17.0.19` for CLI builds.
- **Maven**: 3.9.11
- **Spring Boot**: 3.2.0

## Architecture

ClawBot is a WeChat bot that integrates LLM chat, image recognition, TTS/ASR, image generation, weather queries, and file summarization. The entry point is `MissionApplication.java` in package `com.example.clawbot`.

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
| `LlmService` | Text chat with conversation history (per-user, max 10 turns), image recognition via vision model | DeepSeek (chat), DashScope (vision) |
| `SpeechService` | TTS (text→WAV), ASR (voice→text), SILK→PCM→WAV decoding, per-user voice preference | DashScope TTS (qwen3-tts-flash), DashScope ASR (qwen3-asr-flash) |
| `WeatherService` | Current weather for a city | 心知天气 (seniverse.com) |
| `ImageGenerationService` | Text→image, handles OpenAI/DashScope response formats | 智谱 CogView-3-Plus |
| `FileSummaryService` | Extract text from PDF/DOCX/XLSX/PPTX/TXT → LLM summary | Apache PDFBox + POI, DeepSeek |

### Exception handling

`exception/` package contains:
- `Result<T>` — unified response wrapper with static `error()` factory
- `BusinessException` — runtime exception carrying a business error code
- `GlobalExceptionHandler` — `@RestControllerAdvice` catching `BusinessException` (warn), `MethodArgumentNotValidException` (400), and generic `Exception` (500)

### Configuration

- `RestTemplateConfig` provides a plain `RestTemplate` bean (no interceptors — auth is set per-request in each service)
- `application.properties` is gitignored; `application-example.properties` serves as the template with placeholder values for all API keys

## Dependencies

- **spring-boot-starter-web** — Spring MVC + Tomcat
- **spring-boot-starter-data-mongodb** — MongoDB driver
- **mysql-connector-j** — MySQL driver (runtime scope)
- **wechat-ilink-sdk** — WeChat ILink bot SDK
- **pdfbox 3.0.4** — PDF text extraction
- **poi-ooxml 5.2.5** — Word/Excel/PPT text extraction
- **lombok** — annotation processing
