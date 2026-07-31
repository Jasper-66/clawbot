# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Backend
mvn clean compile
mvn clean test
mvn clean package
mvn spring-boot:run

# Frontend (Vue 3 + Vite)
cd clawbot-ui && npm install && npm run dev
```

Use `JAVA_HOME=C:/Users/30287/.jdks/ms-21.0.12` for CLI builds on this machine.

## Environment

- **JDK**: JDK 21 (Microsoft OpenJDK `ms-21.0.12`)
- **Maven**: 3.9.11
- **Spring Boot**: 3.3.5
- **Spring AI**: 1.0.0-M6 (OpenAI starter for DeepSeek/DashScope compatibility)

## Architecture

ClawBot is a WeChat bot that integrates LLM chat, image recognition, TTS/ASR, image generation, weather queries, file summarization, resume parsing, and auto job application. Entry point: `ClawBotApplication.java` in package `com.example.clawbot`.

### Message flow

```
WeChatBotService (polling loop, message router)
  ├── text → weather? → WeatherService
  ├── text → image gen? → ImageGenerationService
  ├── text → TTS request? → SpeechService.textToSpeech()
  ├── text → other → LlmService.chat() (with Function Calling)
  ├── image → LlmService.chatWithImage() (vision API)
  ├── voice → SpeechService.speechToText() → route as text
  └── file → FileSummaryService (extract text with PDFBox/POI → LLM summary)
```

`WeChatBotService` uses `wechat-ilink-sdk` (ILinkClient) for WeChat connectivity. It logs in via QR code, then polls for messages every 2 seconds. Message deduplication uses a `ConcurrentHashMap.newKeySet()` of message IDs.

### Function Calling (Tool System)

Tool classes in `tool/` use `@Tool` annotation. `LlmService` injects them via constructor and passes to `ChatClient.Builder.defaultTools()`. Spring AI handles the function calling loop automatically.

| Tool | Function Name | Description |
|---|---|---|
| `WeatherTool` | `get_weather` | Query real-time weather by city |
| `GeocodeTool` | `geocode` | Convert place name to lat/lng via Amap |
| `SearchNearbyTool` | `search_nearby` | Search POIs near a location via Amap |
| `PlanRouteTool` | `plan_route` | Plan driving route via Amap |
| `SearchTool` | `web_search` | Web search via Tavily API |
| `TextToSpeechTool` | `text_to_speech` | TTS via DashScope, returns audio file path |
| `ReminderTool` | `create_reminder` / `create_periodic_reminder` | One-time and periodic reminders |
| `TarotTool` | `tarot_reading` | Tarot card reading with multiple spread types |
| `ResumeTool` | `search_jobs` / `auto_apply` / `get_application_progress` | Resume-based job search and auto-apply |

### Resume Module (7-Member Architecture)

The `resume/` package implements an auto job-application pipeline with 7 collaborating members:

```
ResumeTool (LLM tool entry)
  └── ResumeOrchestrator (指挥家, orchestrator)
        ├── ResumeParser (成员1) — parse resume → UserProfile
        ├── JobSearchClient (成员2) — search jobs on external platform
        ├── MatchScorer (成员3) — LLM-based matching score (0-100)
        ├── ResumeOptimizer (成员4) — generate optimization tips per job
        ├── ApplicationClient (成员5) — submit application to platform
        └── ApplicationTracker (成员6) — record and track applications
```

Key interfaces: `ResumeOrchestrator`, `MatchScorer`, `ResumeParser`, `ResumeOptimizer`, `ApplicationTracker`, `JobSearchClient`, `ApplicationClient`.

### RAG Knowledge Base

The `knowledge/` package provides Retrieval-Augmented Generation (RAG):

```
User question → KnowledgeRetriever.retrieve(query)
                    ↓
              VectorStore.similaritySearch() (SimpleVectorStore, JSON file)
                    ↓
              Top-K relevant doc chunks → injected into System Prompt
                    ↓
              LLM generates answer with knowledge context
```

- **Embedding**: DashScope `text-embedding-v3` via `OpenAiEmbeddingModel`
- **Vector Store**: `SimpleVectorStore` persisted to `knowledge-vectors.json`
- **Document Parsing**: Apache Tika extracts text from PDF/DOCX/TXT/HTML
- **Text Splitting**: `TokenTextSplitter` (200 tokens per chunk)

REST API: `KnowledgeController` at `/api/knowledge/*` — document CRUD, file upload, search test.

### Frontend (Vue 3)

`clawbot-ui/` — Vue 3 + Vite + Element Plus frontend for knowledge base management:

- `/documents` — document list with category filter and delete
- `/upload` — file upload (PDF/DOCX/TXT) and manual text input
- `/search` — RAG retrieval test with Top-K control

Vite dev server proxies `/api` to `http://localhost:8080`.

### Data Layer (SQLite)

SQLite stores conversation history and reminders. `DatabaseInitializer` creates tables at startup via `@PostConstruct`:

- `ConversationRepository` — conversation sessions per user
- `MessageRepository` — chat messages per conversation
- `ReminderRepository` — scheduled reminders
- `SqliteChatMemory` — implements Spring AI `ChatMemory` for conversation context

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
- **spring-boot-starter-data-redis** — Redis for conversation memory
- **spring-ai-openai-spring-boot-starter** (1.0.0-M6) — Spring AI with OpenAI compatibility (DeepSeek + DashScope embeddings)
- **tika-core 2.9.2** — document text extraction for RAG knowledge base
- **wechat-ilink-sdk** — WeChat ILink bot SDK
- **sqlite-jdbc** — SQLite driver
- **pdfbox 3.0.4** — PDF text extraction
- **poi-ooxml 5.2.5** — Word/Excel/PPT text extraction
- **lombok** — annotation processing
