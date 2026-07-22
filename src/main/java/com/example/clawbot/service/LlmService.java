package com.example.clawbot.service;

import com.example.clawbot.tool.GeocodeTool;
import com.example.clawbot.tool.PlanRouteTool;
import com.example.clawbot.tool.SearchNearbyTool;
import com.example.clawbot.tool.TextToSpeechTool;
import com.example.clawbot.tool.WeatherTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestTemplate restTemplate;
    private final WeatherTool weatherTool;
    private final GeocodeTool geocodeTool;
    private final SearchNearbyTool searchNearbyTool;
    private final PlanRouteTool planRouteTool;
    private final TextToSpeechTool textToSpeechTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, LinkedList<Map<String, Object>>> conversations = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 10;
    private static final int MAX_TOOL_ROUNDS = 30;

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

    private static final String SYSTEM_PROMPT =
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。\n"
                    + "如果调用了 text_to_speech 工具生成了语音，请务必在回复中保留 [audio:工具返回的file_path] 标记，以便系统发送给用户语音消息。";

    public String chat(String userId, String userMessage) {
        LinkedList<Map<String, Object>> history = conversations.computeIfAbsent(userId, k -> new LinkedList<>());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

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
                textToSpeechTool.getToolDefinition()
        ));
        requestBody.put("tool_choice", "auto");

        String reply = callLlmWithTools(requestBody, messages);

        synchronized (history) {
            history.add(Map.of("role", "user", "content", userMessage));
            history.add(Map.of("role", "assistant", "content", reply));
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }

        return reply.trim();
    }

    /**
     * 完成 Function Calling 闭环：请求模型、执行工具、回传结果，再获取最终回答。
     */
    private String callLlmWithTools(Map<String, Object> requestBody,
                                    List<Map<String, Object>> messages) {
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
        if (weatherTool.getToolName().equals(functionName)) {
            return weatherTool.execute(functionName, arguments);
        }
        if (geocodeTool.getToolName().equals(functionName)) {
            return geocodeTool.execute(functionName, arguments);
        }
        if (searchNearbyTool.getToolName().equals(functionName)) {
            return searchNearbyTool.execute(functionName, arguments);
        }
        if (planRouteTool.getToolName().equals(functionName)) {
            return planRouteTool.execute(functionName, arguments);
        }
        if (textToSpeechTool.getToolName().equals(functionName)) {
            return textToSpeechTool.execute(functionName, arguments);
        }
        return "工具调用失败：未找到工具 " + functionName;
    }

    private Map<String, Object> toAssistantToolCallMessage(JsonNode assistant) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", assistant.path("content").isNull()
                ? null : assistant.path("content").asText());
        message.put("tool_calls", objectMapper.convertValue(assistant.path("tool_calls"), List.class));

        // DeepSeek 思考模式调用工具后，后续请求必须原样带回 reasoning_content
        if (assistant.hasNonNull("reasoning_content")) {
            message.put("reasoning_content", assistant.get("reasoning_content").asText());
        }
        return message;
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

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        LinkedList<Map<String, Object>> history = conversations.get(userId);
        if (history != null) {
            synchronized (history) {
                messages.addAll(history);
            }
        }
        messages.add(Map.of("role", "user", "content", contentParts));

        Map<String, Object> requestBody = Map.of(
                "model", visionModel,
                "messages", messages,
                "max_tokens", 1024
        );

        String reply = callLlm(visionBaseUrl, visionApiKey, requestBody);

        LinkedList<Map<String, Object>> h = conversations.computeIfAbsent(userId, k -> new LinkedList<>());
        synchronized (h) {
            h.add(Map.of("role", "user", "content", "[发送了一张图片]"));
            h.add(Map.of("role", "assistant", "content", reply));
            while (h.size() > MAX_HISTORY) {
                h.removeFirst();
            }
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
