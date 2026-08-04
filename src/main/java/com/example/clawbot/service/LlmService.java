package com.example.clawbot.service;

import com.example.clawbot.knowledge.service.KnowledgeRetriever;
import com.example.clawbot.repository.ConversationRepository;
import com.example.clawbot.repository.MessageRepository;
import com.example.clawbot.repository.SqliteChatMemory;
import com.example.clawbot.repository.TokenUsageRepository;
import com.example.clawbot.tool.GeocodeTool;
import com.example.clawbot.tool.PlanRouteTool;
import com.example.clawbot.tool.ReminderTool;
import com.example.clawbot.tool.SearchNearbyTool;
import com.example.clawbot.tool.TarotTool;
import com.example.clawbot.tool.TextToSpeechTool;
import com.example.clawbot.tool.WeatherTool;
import com.example.clawbot.resume.tool.ResumeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;
import java.time.ZoneId;

// LLM 对话与图片识别服务，封装 DeepSeek Chat（文本）和 DashScope（视觉）的 API 调用
@Slf4j
@Service
public class LlmService {

    private final RestTemplate restTemplate;
    private final ChatClient chatClient;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final KnowledgeRetriever knowledgeRetriever;
    private final TokenUsageRepository tokenUsageRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    @Value("${vision.api.key}")
    private String visionApiKey;

    @Value("${vision.api.base-url}")
    private String visionBaseUrl;

    @Value("${vision.api.model}")
    private String visionModel;

    public LlmService(RestTemplate restTemplate,
                      ChatClient.Builder chatClientBuilder,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      KnowledgeRetriever knowledgeRetriever,
                      TokenUsageRepository tokenUsageRepository,
                      SqliteChatMemory chatMemory,
                      WeatherTool weatherTool,
                      GeocodeTool geocodeTool,
                      SearchNearbyTool searchNearbyTool,
                      PlanRouteTool planRouteTool,
                      TextToSpeechTool textToSpeechTool,
                      ReminderTool reminderTool,
                      TarotTool tarotTool,
                      ResumeTool resumeTool,
                      SyncMcpToolCallbackProvider mcpToolCallbackProvider) {
        this.restTemplate = restTemplate;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.knowledgeRetriever = knowledgeRetriever;
        this.tokenUsageRepository = tokenUsageRepository;
        this.chatClient = chatClientBuilder

                .defaultTools(
                        weatherTool,
                        geocodeTool,
                        searchNearbyTool,
                        planRouteTool,
                        textToSpeechTool,
                        reminderTool,
                        tarotTool,
                        resumeTool
                )
                .defaultToolCallbacks(mcpToolCallbackProvider.getToolCallbacks())
                //关键：配置记忆顾问（秘书）
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @PostConstruct
    public void init() {
        if (visionApiKey != null) visionApiKey = visionApiKey.trim();
        if (visionBaseUrl != null) visionBaseUrl = visionBaseUrl.trim();
        if (visionModel != null) visionModel = visionModel.trim();
        log.info("LLM 配置初始化完成: visionBaseUrl={}, visionModel={}", visionBaseUrl, visionModel);
    }

    private static final String SYSTEM_PROMPT =
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。\n"
                    + "【重要规则-语音】当用户明确要求语音回复、朗读、播报、讲故事/笑话等需要以语音形式呈现内容时，"
                    + "你必须先生成回复内容，然后调用 text_to_speech 工具将内容转为语音。"
                    + "调用工具后，在最终回复中保留 [audio:工具返回的file_path] 标记，以便系统发送语音给用户。\n"
                    + "如果用户没有要求语音，不要主动调用 text_to_speech 工具。\n"
                    + "【重要规则-提醒】当用户要求设置提醒、定时提醒时，你必须调用 create_reminder 或 create_periodic_reminder 工具。"
                    + "绝对不要自己编造'已设置成功'的回复，只有工具返回 success=true 才算设置成功。"
                    + "如果用户没有提供明确时间，先询问用户。\n"
                    + "【重要规则-求职】查询岗位时必须调用本地 search_jobs；只有查询猎聘账号中的在线简历时才调用 my-resume。"
                    + "用户说“投1号”“投1和3号”或“全部投递”时，必须将用户这句话原样传给 apply_jobs，"
                    + "使用最近一次 search_jobs 的岗位结果立即投递。只能根据工具真实返回结果回复，不得编造投递成功。";

    public String chat(String userId, String userMessage) {
        long startTime = System.currentTimeMillis();
        String messagePreview = userMessage.length() > 100
                ? userMessage.substring(0, 100) + "..."
                : userMessage;
        log.info("[行动] 向 DeepSeek API 发送对话请求 (model={})", chatClient != null ? "deepseek-v4-flash" : "unknown");
        log.info("  请求内容: system提示词 + Function Calling工具定义 + 对话历史 + 用户消息 \"{}\"", messagePreview);

        // ① 获取或创建会话ID
        String conversationId = conversationRepository.getOrCreate(userId, userMessage);

        try {
            // ② RAG 检索知识库
            String ragContext = "";
            try {
                ragContext = knowledgeRetriever.retrieve(userMessage);
                if (!ragContext.isEmpty()) {
                    log.info("[RAG] 检索到相关知识内容 ({}字符)", ragContext.length());
                }
            } catch (Exception e) {
                log.warn("[RAG] 知识库检索失败，继续正常对话: {}", e.getMessage());
            }

            // ③ 使用 Fluent API，Advisor 自动管记忆
            var chatResponse = chatClient.prompt()
                    .system(buildSystemPrompt(ragContext))
                    .user(userMessage)
                    .toolContext(Map.of("userId", userId))
                    .advisors(a -> a.param(
                            ChatMemory.CONVERSATION_ID,
                            conversationId
                    ))
                    .call()
                    .chatResponse();

            String reply = chatResponse.getResult().getOutput().getText();

            // ④ 记录 Token 消耗
            try {
                var usage = chatResponse.getMetadata().getUsage();
                if (usage != null) {
                    int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                    int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                    int totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : (promptTokens + completionTokens);
                    tokenUsageRepository.record(conversationId, promptTokens, completionTokens, totalTokens, "deepseek");
                    log.info("[Token] prompt={}, completion={}, total={}", promptTokens, completionTokens, totalTokens);
                }
            } catch (Exception e) {
                log.warn("[Token] 记录token消耗失败: {}", e.getMessage());
            }

            if (reply == null || reply.isBlank()) {
                log.warn("[观察] LLM 返回空内容，未生成文本或工具调用");
                return "AI 没有完成本次操作，请重新发送刚才的消息；涉及投递时可先查询投递进度，避免重复操作。";
            }
            return reply.trim();
        } catch (Exception e) {
            log.error("ChatClient 调用失败", e);
            return "抱歉，我暂时无法处理，请稍后再试。";
        }
    }

    private String buildSystemPrompt(String ragContext) {
        String currentTime = OffsetDateTime.now(DEFAULT_ZONE).toString();
        String prompt = SYSTEM_PROMPT
                + " 当前时间是 " + currentTime + "，当前时区是 Asia/Shanghai。"
                + " 创建提醒时必须把用户表达的时间转换为带时区的 ISO 8601 格式；"
                + "如果用户没有提供明确时间，应先询问用户。";

        if (ragContext != null && !ragContext.isEmpty()) {
            prompt += "\n\n【知识库参考内容】\n以下是与用户问题相关的知识库内容，请参考这些信息来回答问题。"
                    + "如果知识库中有相关内容，请优先引用并标注来源。如果知识库中没有相关内容，请根据你的知识回答。\n\n"
                    + ragContext;
        }

        return prompt;
    }

    public String chatWithImage(String userId, byte[] imageBytes, String fileName) {
        long startTime = System.currentTimeMillis();
        log.info("[行动] 调用 DashScope 视觉模型 (model=qwen3.7-plus): 上传图片 ({} bytes) 并请求内容描述", imageBytes.length);

        String conversationId = conversationRepository.getOrCreate(userId, "[发送了一张图片]");

        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mimeType = getMimeType(fileName);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        try {
            List<Map<String, Object>> contentParts = new ArrayList<>();
            contentParts.add(Map.of("type", "text", "text",
                    "请详细描述这张图片的内容。用友好、简洁的中文回复，控制在200字以内。"));
            contentParts.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUrl)
            ));

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

            List<Map<String, Object>> history = messageRepository.findRecentByConversationId(conversationId);
            for (Map<String, Object> row : history) {
                messages.add(Map.of("role", row.get("role"), "content", row.get("content")));
            }

            messages.add(Map.of("role", "user", "content", contentParts));

            Map<String, Object> requestBody = Map.of(
                    "model", visionModel,
                    "messages", messages,
                    "max_tokens", 1024
            );

            String reply = callLlm(visionBaseUrl, visionApiKey, requestBody);

            messageRepository.insert(conversationId, "user", "[发送了一张图片]", "image", null);
            messageRepository.insert(conversationId, "assistant", reply, "text", null);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[观察] DashScope 视觉模型返回描述 ({}字符, 耗时{}ms)",
                    reply != null ? reply.length() : 0, elapsed);
            return reply;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[异常] DashScope 视觉模型调用失败 (耗时{}ms) | 原因: {} | 建议: 检查 vision.api.key 配置",
                    elapsed, e.getMessage(), e);
            return "抱歉，图片识别暂时不可用。";
        }
    }

    private String callLlm(String apiUrl, String key, Map<String, Object> requestBody) {
        try {
            JsonNode message = callChatCompletion(apiUrl, key, requestBody);
            String content = message.path("content").asText("").trim();
            log.info("LLM 回复: {}", content);
            return content.isEmpty() ? "抱歉，我暂时无法处理，请稍后再试。" : content;
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            return "抱歉，AI 服务暂时不可用。";
        }
    }

    private JsonNode callChatCompletion(String apiUrl, String key,
                                        Map<String, Object> requestBody) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(key);
        String requestJson = objectMapper.writeValueAsString(requestBody);
        log.info("LLM 请求: model={}, url={}", requestBody.get("model"), apiUrl);
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl + "/v1/chat/completions", entity, String.class);

        String responseBody = response.getBody();
        if (responseBody == null) {
            throw new IllegalStateException("LLM 返回空响应");
        }

        JsonNode root = objectMapper.readTree(responseBody);
        if (root.has("error")) {
            throw new IllegalStateException("LLM API 错误: " + root.get("error"));
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()
                || choices.get(0).path("message").isMissingNode()) {
            throw new IllegalStateException("LLM 响应缺少 choices[0].message");
        }
        return choices.get(0).path("message");
    }

    private String getMimeType(String fileName) {
        if (fileName == null) return "image/png";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }
}
