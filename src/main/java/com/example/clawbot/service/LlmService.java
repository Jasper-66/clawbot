package com.example.clawbot.service;

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
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 每个用户最近 N 条消息历史，userId → 消息列表
    private final ConcurrentHashMap<String, LinkedList<Map<String, Object>>> conversations = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 10;

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
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。"; // 系统角色提示词，设定助手行为风格

    // 工具定义 — Function Calling 注册表
    private static final List<Map<String, Object>> TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "get_weather",
                            "description", "查询指定城市的当前天气信息，包括温度、天气状况、湿度、风向风力等",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "city", Map.of(
                                                    "type", "string",
                                                    "description", "城市名称，如：杭州、北京、上海"
                                            )
                                    ),
                                    "required", List.of("city")
                            )
                    )
            )
    );

    // 工具执行器注册表：name → 执行函数
    private final Map<String, Function<String, String>> toolExecutors = new HashMap<>();

    // 注册工具执行器（由各 Service 在启动时调用）
    public void registerTool(String name, Function<String, String> executor) {
        toolExecutors.put(name, executor);
    }

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
                "temperature", 0.7,
                "max_tokens", 1024
        ));

        String reply = callLlm(baseUrl, apiKey, requestBody);

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

    // 带 Function Calling 的对话：LLM 自行决定是否调用工具
    public String chatWithTools(String userId, String userMessage) {
        LinkedList<Map<String, Object>> history = conversations.computeIfAbsent(userId, k -> new LinkedList<>());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // Function Calling 循环：LLM 可能连续请求多个工具调用
        for (int i = 0; i < 5; i++) {
            Map<String, Object> requestBody = new HashMap<>(Map.of(
                    "model", model,
                    "messages", messages,
                    "tools", TOOLS,
                    "temperature", 0.7,
                    "max_tokens", 1024
            ));

            String responseBody = callLlmRaw(baseUrl, apiKey, requestBody);
            if (responseBody == null) return "抱歉，我暂时无法处理，请稍后再试。";

            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.has("error")) {
                    log.error("LLM API 错误: {}", root.get("error").toString());
                    return "抱歉，AI 服务暂时不可用。";
                }

                JsonNode choice = root.get("choices").get(0);
                JsonNode message = choice.get("message");
                String finishReason = choice.get("finish_reason").asText();

                // 没有工具调用，直接返回文本回复
                if (!"tool_calls".equals(finishReason)) {
                    String content = message.get("content").asText();
                    // 保存对话历史
                    synchronized (history) {
                        history.add(Map.of("role", "user", "content", userMessage));
                        history.add(Map.of("role", "assistant", "content", content));
                        while (history.size() > MAX_HISTORY) {
                            history.removeFirst();
                        }
                    }
                    return content.trim();
                }

                // 有工具调用：把 assistant 的 tool_calls 消息加入 messages
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("tool_calls", objectMapper.treeToValue(message.get("tool_calls"), List.class));
                messages.add(assistantMsg);

                // 逐个执行工具调用，把结果加入 messages
                for (JsonNode toolCall : message.get("tool_calls")) {
                    String callId = toolCall.get("id").asText();
                    String funcName = toolCall.get("function").get("name").asText();
                    String argsJson = toolCall.get("function").get("arguments").asText();

                    log.info("Function Calling: name={}, args={}", funcName, argsJson);

                    String result = executeTool(funcName, argsJson);
                    log.info("Function Result: {}", result);

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", result
                    ));
                }

            } catch (Exception e) {
                log.error("解析 LLM 响应失败", e);
                return "抱歉，处理出错了。";
            }
        }
        return "抱歉，处理轮次过多，请简化问题。";
    }

    // 执行工具调用
    private String executeTool(String toolName, String argsJson) {
        try {
            Function<String, String> executor = toolExecutors.get(toolName);
            if (executor == null) {
                return objectMapper.writeValueAsString(Map.of("error", "未知工具: " + toolName));
            }
            JsonNode args = objectMapper.readTree(argsJson);
            String param = args.get("city") != null ? args.get("city").asText() : args.fields().next().getValue().asText();
            return executor.apply(param);
        } catch (Exception e) {
            log.error("工具执行失败: {}", toolName, e);
            try {
                return objectMapper.writeValueAsString(Map.of("error", "工具执行失败: " + e.getMessage()));
            } catch (Exception ex) {
                return "{\"error\":\"工具执行失败\"}";
            }
        }
    }

    // 原始 LLM 调用，返回响应字符串（供 Function Calling 多轮调用复用）
    private String callLlmRaw(String apiUrl, String key, Map<String, Object> requestBody) {
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
            }
            return responseBody;
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            return null;
        }
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
