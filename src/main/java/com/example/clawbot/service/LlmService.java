package com.example.clawbot.service;

import com.example.clawbot.tool.GeocodeTool;
import com.example.clawbot.tool.PlanRouteTool;
import com.example.clawbot.tool.SearchNearbyTool;
import com.example.clawbot.tool.SearchTool;
import com.example.clawbot.tool.TextToSpeechTool;
import com.example.clawbot.tool.WeatherTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 大语言模型（LLM）对话服务 — 系统的"大脑"，集成长期记忆与 Function Calling。 */
@Slf4j
@Service
public class LlmService {

    private final RestTemplate restTemplate;
    private final ConversationMemoryService memoryService;

    private final WeatherTool weatherTool;
    private final GeocodeTool geocodeTool;
    private final SearchNearbyTool searchNearbyTool;
    private final PlanRouteTool planRouteTool;
    private final TextToSpeechTool textToSpeechTool;
    private final SearchTool searchTool;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_TOOL_ROUNDS = 30;
    private static final int COMPRESSION_BATCH = 16;

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.base-url}")
    private String baseUrl;

    @Value("${deepseek.api.model}")
    private String model;

    @Value("${vision.api.key}")
    private String visionApiKey;

    @Value("${vision.api.base-url}")
    private String visionBaseUrl;

    @Value("${vision.api.model}")
    private String visionModel;

    public static final String SYSTEM_PROMPT =
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。";

    public LlmService(RestTemplate restTemplate, ConversationMemoryService memoryService,
                      WeatherTool weatherTool, GeocodeTool geocodeTool,
                      SearchNearbyTool searchNearbyTool, PlanRouteTool planRouteTool,
                      TextToSpeechTool textToSpeechTool, SearchTool searchTool) {
        this.restTemplate = restTemplate;
        this.memoryService = memoryService;
        this.weatherTool = weatherTool;
        this.geocodeTool = geocodeTool;
        this.searchNearbyTool = searchNearbyTool;
        this.planRouteTool = planRouteTool;
        this.textToSpeechTool = textToSpeechTool;
        this.searchTool = searchTool;
    }

    /** 文本对话：从长期记忆获取上下文 → LLM 推理 → 追加消息到记忆 → 必要时压缩历史 */
    public String chat(String userId, String userMessage) {
        // 1. 从长期记忆中加载上下文
        List<Map<String, Object>> contextMessages = memoryService.getContext(userId);

        // 2. 组装完整消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.addAll(contextMessages);
        messages.add(Map.of("role", "user", "content", userMessage));

        // 3. 构建请求体（含工具定义）
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 1024);
        requestBody.put("tools", List.of(
                weatherTool.getToolDefinition(),
                geocodeTool.getToolDefinition(),
                searchNearbyTool.getToolDefinition(),
                planRouteTool.getToolDefinition(),
                textToSpeechTool.getToolDefinition(),
                searchTool.getToolDefinition()
        ));
        requestBody.put("tool_choice", "auto");

        // 4. Function Calling 闭环
        String[] ttsFilePathHolder = new String[1];
        String reply = callLlmWithTools(requestBody, messages, ttsFilePathHolder);

        if (ttsFilePathHolder[0] != null) {
            reply = "[audio:" + ttsFilePathHolder[0] + "]" + reply;
        }

        // 5. 追加消息到长期记忆
        memoryService.appendMessage(userId, "user", userMessage);
        memoryService.appendMessage(userId, "assistant", reply);

        // 6. 检查是否需要压缩旧对话为长期记忆摘要
        if (memoryService.needsCompression(userId)) {
            compressHistoryAsync(userId);
        }

        return reply.trim();
    }

    /** 异步压缩旧对话为长期记忆摘要，不阻塞当前回复 */
    private void compressHistoryAsync(String userId) {
        try {
            List<Map<String, Object>> oldMessages = memoryService.extractForCompression(userId, COMPRESSION_BATCH);
            if (oldMessages.isEmpty()) return;

            String summary = summarizeMessages(oldMessages);
            if (summary != null && !summary.isBlank()) {
                memoryService.appendLongTermMemory(userId, summary);
            }
        } catch (Exception e) {
            log.warn("压缩对话历史失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /** 调用 LLM 将旧消息压缩为要点摘要 */
    private String summarizeMessages(List<Map<String, Object>> messages) {
        StringBuilder transcript = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            transcript.append(role).append(": ").append(content).append("\n");
        }

        String prompt = "请用一段简洁的中文（不超过150字）总结以下对话的核心内容和关键信息。只输出摘要文本，不要加任何前缀：\n\n" + transcript;

        List<Map<String, Object>> msgs = List.of(
                Map.of("role", "system", "content", "你是一个对话摘要助手，请用简洁的中文提取对话中的关键信息。"),
                Map.of("role", "user", "content", prompt)
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("temperature", 0.3);
        body.put("max_tokens", 256);

        try {
            JsonNode assistant = callChatCompletion(baseUrl, apiKey, body);
            String summary = assistant.path("content").asText("").trim();
            return summary.isEmpty() ? null : summary;
        } catch (Exception e) {
            log.warn("生成对话摘要失败: {}", e.getMessage());
            return null;
        }
    }

    // ─── Function Calling 引擎 ────────────────────────────

    private String callLlmWithTools(Map<String, Object> requestBody,
                                    List<Map<String, Object>> messages,
                                    String[] ttsFilePathOut) {
        try {
            for (int toolRound = 0; toolRound <= MAX_TOOL_ROUNDS; toolRound++) {
                JsonNode assistant = callChatCompletion(baseUrl, apiKey, requestBody);
                JsonNode toolCalls = assistant.path("tool_calls");

                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    String content = assistant.path("content").asText("").trim();
                    return content.isEmpty() ? "抱歉，我没有生成有效回复，请稍后再试。" : content;
                }

                if (toolRound == MAX_TOOL_ROUNDS) {
                    return "抱歉，工具调用次数过多，请换一种方式提问。";
                }

                messages.add(toAssistantToolCallMessage(assistant));

                for (JsonNode toolCall : toolCalls) {
                    String toolCallId = toolCall.path("id").asText("");
                    if (toolCallId.isBlank()) {
                        throw new IllegalStateException("工具调用缺少 id");
                    }

                    JsonNode function = toolCall.path("function");
                    String functionName = function.path("name").asText("");
                    String arguments = function.path("arguments").asText("{}");
                    String toolResult = executeTool(functionName, arguments);
                    log.info("执行工具: name={}, id={}", functionName, toolCallId);

                    if (textToSpeechTool.getToolName().equals(functionName)) {
                        try {
                            JsonNode resultNode = objectMapper.readTree(toolResult);
                            String ttsPath = resultNode.path("file_path").asText(null);
                            if (ttsPath != null) {
                                ttsFilePathOut[0] = ttsPath;
                            }
                        } catch (Exception e) {
                            log.warn("解析 TTS 工具返回的文件路径失败: {}", e.getMessage());
                        }
                    }

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", toolCallId,
                            "content", toolResult
                    ));
                }
            }
            return "抱歉，我暂时无法处理，请稍后再试。";
        } catch (Exception e) {
            log.error("Function Calling 调用失败", e);
            return "抱歉，我暂时无法处理，请稍后再试。";
        }
    }

    private String executeTool(String functionName, String arguments) {
        if (weatherTool.getToolName().equals(functionName)) return weatherTool.execute(functionName, arguments);
        if (geocodeTool.getToolName().equals(functionName)) return geocodeTool.execute(functionName, arguments);
        if (searchNearbyTool.getToolName().equals(functionName)) return searchNearbyTool.execute(functionName, arguments);
        if (planRouteTool.getToolName().equals(functionName)) return planRouteTool.execute(functionName, arguments);
        if (textToSpeechTool.getToolName().equals(functionName)) return textToSpeechTool.execute(functionName, arguments);
        if (searchTool.getToolName().equals(functionName)) return searchTool.execute(functionName, arguments);
        return "工具调用失败：未找到工具 " + functionName;
    }

    private Map<String, Object> toAssistantToolCallMessage(JsonNode assistant) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", assistant.path("content").isNull()
                ? null : assistant.path("content").asText());
        message.put("tool_calls", objectMapper.convertValue(assistant.path("tool_calls"), List.class));
        if (assistant.hasNonNull("reasoning_content")) {
            message.put("reasoning_content", assistant.get("reasoning_content").asText());
        }
        return message;
    }

    // ─── LLM API 调用 ──────────────────────────────────────

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

    // ─── 图片识别 ──────────────────────────────────────────

    public String chatWithImage(String userId, byte[] imageBytes, String fileName) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mimeType = getMimeType(fileName);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text",
                "请详细描述这张图片的内容。用友好、简洁的中文回复，控制在200字以内。"));
        contentParts.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUrl)
        ));

        // 从长期记忆中加载上下文
        List<Map<String, Object>> contextMessages = memoryService.getContext(userId);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.addAll(contextMessages);
        messages.add(Map.of("role", "user", "content", contentParts));

        Map<String, Object> requestBody = Map.of(
                "model", visionModel,
                "messages", messages,
                "max_tokens", 1024
        );

        String reply = callLlm(visionBaseUrl, visionApiKey, requestBody);

        // 追加到长期记忆
        memoryService.appendMessage(userId, "user", "[发送了一张图片]");
        memoryService.appendMessage(userId, "assistant", reply);

        if (memoryService.needsCompression(userId)) {
            compressHistoryAsync(userId);
        }

        return reply;
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
