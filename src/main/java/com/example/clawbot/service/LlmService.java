package com.example.clawbot.service;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class    LlmService {

    private final RestTemplate restTemplate;
    private final WeatherTool weatherTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 每个用户最近 N 条消息历史，userId → 消息列表
    private final ConcurrentHashMap<String, LinkedList<Map<String, Object>>> conversations = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 10;
    private static final int MAX_TOOL_ROUNDS = 3;

    // DeepSeek 文本对话
    @Value("${deepseek.api.key}")
    private String apiKey; // DeepSeek API 密钥

    @Value("${deepseek.api.base-url}")
    private String baseUrl; // DeepSeek API 地址

    @Value("${deepseek.api.model}")
    private String model; // DeepSeek 模型名称

    // Vision API 图片识别
    @Value("${vision.api.key}")
    private String visionApiKey; // Vision API 密钥

    @Value("${vision.api.base-url}")
    private String visionBaseUrl; // Vision API 地址

    @Value("${vision.api.model}")
    private String visionModel; // Vision 模型名称

    private static final String SYSTEM_PROMPT =
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。"
                    + "用户询问实时天气时必须调用天气工具，不要凭已有知识编造天气。"; // 系统角色提示词，设定助手行为风格

    //调用LLM API 进行纯文本对话，自动维护用户上下文
    public String chat(String userId, String userMessage) {
        LinkedList<Map<String, Object>> history = conversations.computeIfAbsent(userId, k -> new LinkedList<>());

        // 构建消息列表：system + 历史 + 当前用户消息
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new HashMap<>(Map.of(
                "model", model,
                "messages", messages,
                "tools", List.of(weatherTool.definition()),
                "tool_choice", "auto",
                "temperature", 0.7,
                "max_tokens", 1024
        ));

        String reply = callLlmWithTools(requestBody, messages);

        // 将本轮对话加入历史，保持最近 MAX_HISTORY 条
        synchronized (history) {
            history.add(Map.of("role", "user", "content", userMessage));
            history.add(Map.of("role", "assistant", "content", reply));
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }

        return reply;
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
                    String toolResult = weatherTool.execute(functionName, arguments);
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

    private Map<String, Object> toAssistantToolCallMessage(JsonNode assistant) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", assistant.path("content").isNull()
                ? null : assistant.path("content").asText());
        message.put("tool_calls", objectMapper.convertValue(assistant.path("tool_calls"), List.class));

        // DeepSeek 思考模式调用工具后，后续请求必须原样带回 reasoning_content。
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

    // 调用 Vision API 解析图片内容，携带文本对话上下文
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

        // 构建消息列表：system + 文本历史（跳过图片消息）+ 当前图片消息
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

        // 将本轮对话（纯文本形式）加入历史
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

    // 通用 LLM API 调用方法，支持文本和图片两种场景复用
    private String callLlm(String apiUrl, String key, Map<String, Object> requestBody) {
        try {
            // 设置请求头：JSON 格式 + Bearer Token 鉴权
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(key);

            // 将请求体序列化为 JSON 字符串，发送 POST 请求
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.info("LLM 请求: model={}, url={}", requestBody.get("model"), apiUrl);

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl + "/v1/chat/completions", entity, String.class);

            // 检查响应体是否为空
            String responseBody = response.getBody();
            if (responseBody == null) {
                log.error("LLM 返回空响应, status={}", response.getStatusCode());
                return "抱歉，我暂时无法处理，请稍后再试。";
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // 检查 API 是否返回了错误
            if (root.has("error")) {
                log.error("LLM API 错误: {}", root.get("error").toString());
                return "抱歉，AI 服务暂时不可用。";
            }

            // 提取 assistant 的回复内容
            String content = root.get("choices").get(0).get("message").get("content").asText();
            log.info("LLM 回复: {}", content);
            return content.trim();

        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            return "抱歉，我暂时无法处理，请稍后再试。";
        }
    }



    // 根据文件名后缀推断 MIME 类型，未知后缀默认返回 image/png
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
