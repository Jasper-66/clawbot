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
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, LinkedList<Map<String, Object>>> conversations = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 10;

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
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。";

    public String chat(String userId, String userMessage) {
        LinkedList<Map<String, Object>> history = conversations.computeIfAbsent(userId, k -> new LinkedList<>());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // 构建带 tools 的请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 1024);
        requestBody.put("tools", List.of(weatherTool.getToolDefinition()));
        requestBody.put("tool_choice", "auto");

        JsonNode response = callLlmForJson(baseUrl, apiKey, requestBody);
        if (response == null) {
            return "抱歉，AI 服务暂时不可用。";
        }

        JsonNode choice = response.get("choices").get(0);
        JsonNode message = choice.get("message");

        String reply;

        if (message.has("tool_calls")) {
            // 将 assistant 的 tool_calls 消息加入 messages
            Map<String, Object> assistantMsg = objectMapper.convertValue(message, Map.class);
            messages.add(assistantMsg);

            JsonNode toolCalls = message.get("tool_calls");
            for (JsonNode tc : toolCalls) {
                String funcName = tc.get("function").get("name").asText();
                String args = tc.get("function").get("arguments").asText();
                String callId = tc.get("id").asText();

                String toolResult;
                if (weatherTool.getToolName().equals(funcName)) {
                    toolResult = weatherTool.execute(args);
                } else {
                    toolResult = "未知的工具：" + funcName;
                }

                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", toolResult
                ));
            }

            // 第二次调用，不传 tools，获取最终文本回复
            Map<String, Object> secondBody = new HashMap<>();
            secondBody.put("model", model);
            secondBody.put("messages", messages);
            secondBody.put("temperature", 0.7);
            secondBody.put("max_tokens", 1024);

            JsonNode finalResponse = callLlmForJson(baseUrl, apiKey, secondBody);
            if (finalResponse == null) {
                return "抱歉，我暂时无法处理，请稍后再试。";
            }
            reply = finalResponse.get("choices").get(0).get("message").get("content").asText();
        } else {
            reply = message.get("content").asText();
        }

        if (reply == null) {
            reply = "抱歉，我暂时无法处理，请稍后再试。";
        }

        // 只存储 user 消息和最终 assistant 回复到历史
        synchronized (history) {
            history.add(Map.of("role", "user", "content", userMessage));
            history.add(Map.of("role", "assistant", "content", reply));
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }

        return reply.trim();
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

    // 返回 JsonNode，供 function calling 流程使用
    private JsonNode callLlmForJson(String apiUrl, String key, Map<String, Object> requestBody) {
        try {
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
                log.error("LLM 返回空响应, status={}", response.getStatusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);

            if (root.has("error")) {
                log.error("LLM API 错误: {}", root.get("error").toString());
                return null;
            }

            return root;

        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String callLlm(String apiUrl, String key, Map<String, Object> requestBody) {
        JsonNode root = callLlmForJson(apiUrl, key, requestBody);
        if (root == null) {
            return "抱歉，AI 服务暂时不可用。";
        }
        try {
            String content = root.get("choices").get(0).get("message").get("content").asText();
            log.info("LLM 回复: {}", content);
            return content != null ? content.trim() : "抱歉，我暂时无法处理，请稍后再试。";
        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", e.getMessage());
            return "抱歉，我暂时无法处理，请稍后再试。";
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
