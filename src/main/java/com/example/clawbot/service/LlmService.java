package com.example.clawbot.service;

import com.example.clawbot.memory.JpaChatMemory;
import com.example.clawbot.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 大语言模型（LLM）对话服务。
 *
 * <p>使用 RestTemplate 直接调用 DeepSeek API（支持 Function Calling），
 * 聊天记忆通过 {@link JpaChatMemory} 持久化到 SQLite。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestTemplate restTemplate;
    private final JpaChatMemory chatMemory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final WeatherTool weatherTool;
    private final DateTimeTool dateTimeTool;
    private final TextToSpeechTool textToSpeechTool;
    private final GeocodeTool geocodeTool;
    private final SearchNearbyTool searchNearbyTool;
    private final PlanRouteTool planRouteTool;
    private final TarotTool tarotTool;
    private final RemindTool remindTool;
    private final ScheduledTaskTool scheduledTaskTool;
    private final ImageGenerationTool imageGenerationTool;

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

    private static final int MAX_HISTORY = 100;
    private static final int MAX_TOOL_ROUNDS = 10;

    private static final String SYSTEM_PROMPT =
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。\n"
                    + "【工具调用规则】\n"
                    + "1. 当用户需要执行操作（设置提醒、查天气、生成图片等）时，必须调用对应工具完成。\n"
                    + "2. 绝对禁止在未调用工具或工具未返回成功结果的情况下，声称操作已成功。"
                    + "如果你调用了工具但没有收到成功结果，必须如实告知用户操作未完成。\n"
                    + "3. 需要多步操作时（如先查询再创建），必须逐步调用所有必要的工具，不能跳过任何步骤。\n"
                    + "【语音规则】当用户明确要求语音回复、朗读、播报、讲故事/笑话等需要以语音形式呈现内容时，"
                    + "你必须先生成回复内容，然后调用 text_to_speech 工具将内容转为语音。"
                    + "调用工具后，在最终回复中保留 [audio:工具返回的file_path] 标记，以便系统发送语音给用户。\n"
                    + "如果用户没有要求语音，不要主动调用 text_to_speech 工具。\n"
                    + "【医疗科普】当用户询问医疗健康、疾病预防、症状科普、健康生活方式、医学常识等问题时，"
                    + "你可以直接回答，但必须遵守以下规则：\n"
                    + "1. 仅提供科普知识，严禁提供诊断、处方、用药建议或治疗方案\n"
                    + "2. 回复开头必须加上：【仅供科普参考，不能替代医师诊断，身体不适请及时就医】\n"
                    + "3. 如用户询问具体的诊断或治疗问题，请引导其前往医院就诊";

    /**
     * 文本对话入口 — 使用 RestTemplate 调用 DeepSeek API，支持 Function Calling。
     */
    public String chat(String userId, String userMessage) {
        log.info("========== 新对话开始 ==========");
        log.info("用户 [{}]: {}", userId, userMessage);

        // 1. 从 SQLite 加载历史
        List<Message> history = chatMemory.get(userId, MAX_HISTORY);

        // 2. 组装消息列表
        String today = java.time.LocalDate.now().toString();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                SYSTEM_PROMPT + "\n当前日期：" + today + "，请基于此日期回答用户关于时间、日期的问题。"));

        for (Message msg : history) {
            if (msg instanceof UserMessage um) {
                messages.add(Map.of("role", "user", "content", um.getText()));
            } else if (msg instanceof AssistantMessage am) {
                messages.add(Map.of("role", "assistant", "content", am.getText()));
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // 3. 构建请求体（包含工具定义）
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 1024);
        requestBody.put("tools", List.of(
                weatherTool.getToolDefinition(),
                dateTimeTool.getToolDefinition(),
                textToSpeechTool.getToolDefinition(),
                geocodeTool.getToolDefinition(),
                searchNearbyTool.getToolDefinition(),
                planRouteTool.getToolDefinition(),
                tarotTool.getToolDefinition(),
                remindTool.getToolDefinition(),
                scheduledTaskTool.getToolDefinition(),
                imageGenerationTool.getToolDefinition()
        ));
        requestBody.put("tool_choice", "auto");

        // 4. Function Calling 循环（设置 RemindTool 的用户上下文）
        RemindContextHolder.setUserId(userId);
        String reply;
        try {
            reply = callWithToolLoop(requestBody, messages);
        } finally {
            RemindContextHolder.clear();
        }

        // 5. 保存到 SQLite
        chatMemory.add(userId, List.of(
                new UserMessage(userMessage),
                new AssistantMessage(reply)
        ));

        String finalReply = deduplicateReply(reply.trim());

        log.info("---------- 最终回复给用户 ----------");
        log.info("回复内容: [{}]", finalReply.replace("\n", " | "));
        log.info("========== 对话结束 ==========");
        return finalReply;
    }

    /**
     * Function Calling 循环。
     */
    private String callWithToolLoop(Map<String, Object> requestBody, List<Map<String, Object>> messages) {
        try {
            boolean[] hasCreateAction = {false};
            String lastReply = null;

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                JsonNode assistant = callChatCompletion(requestBody);
                JsonNode toolCalls = assistant.path("tool_calls");

                // 无工具调用 → 检查是否幻觉后返回
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    String content = assistant.path("content").asText("").trim();
                    if (content.isEmpty()) content = "抱歉，我没有生成有效回复，请稍后再试。";

                    // 幻觉检测：LLM 声称操作成功但从未执行创建类操作
                    if (lastReply == null && isHallucinatedSuccess(content, hasCreateAction[0])) {
                        log.warn("检测到 LLM 幻觉：声称成功但未调用创建工具，hasCreateAction={}", hasCreateAction[0]);
                        messages.add(Map.of("role", "user", "content",
                                "你没有调用工具就声称操作成功了，这是错误的！你必须先调用工具（如 remind 的 create 操作）来实际完成用户的请求，然后再回复。"));
                        lastReply = content; // 防止无限重试
                        continue;
                    }
                    return content;
                }

                if (round == MAX_TOOL_ROUNDS) {
                    return "抱歉，工具调用次数过多，请换一种方式提问。";
                }

                // 将助手的工具调用消息加入历史
                messages.add(toAssistantMessage(assistant));

                // 逐个执行工具
                for (JsonNode tc : toolCalls) {
                    String toolCallId = tc.path("id").asText("");
                    String functionName = tc.path("function").path("name").asText("");
                    String arguments = tc.path("function").path("arguments").asText("{}");

                    // 检测是否执行了创建类操作
                    if (isCreateAction(functionName, arguments)) {
                        hasCreateAction[0] = true;
                    }

                    log.info("┌─ 调用工具: {}(参数: {})", functionName, arguments);
                    String result = executeTool(functionName, arguments);
                    log.info("└─ 工具结果: {}", result.length() > 200 ? result.substring(0, 200) + "..." : result);

                    messages.add(Map.of(
                            "role", "tool", "tool_call_id", toolCallId, "content", result
                    ));
                }
                requestBody.put("messages", messages);
            }
            return "抱歉，我暂时无法处理，请稍后再试。";
        } catch (Exception e) {
            log.error("Function Calling 调用失败: {}", e.getMessage(), e);
            return "抱歉，我暂时无法处理，请稍后再试。";
        }
    }

    /**
     * 判断工具调用是否为创建/执行类操作（非查询类）。
     */
    private boolean isCreateAction(String functionName, String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            String action = args.path("action").asText("");
            return "create".equals(action) || "add".equals(action);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测 LLM 是否在未执行创建操作的情况下编造了成功结果。
     *
     * <p>特征：回复包含成功标记（✅、设置成功、已创建等），
     * 但整个对话过程中没有执行过任何 create 类操作。</p>
     */
    private boolean isHallucinatedSuccess(String reply, boolean hasCreateAction) {
        if (reply == null || hasCreateAction) return false;
        return reply.contains("✅") || reply.contains("设置成功")
                || reply.contains("已创建") || reply.contains("已帮你创建")
                || reply.contains("已设置") || reply.contains("提醒ID");
    }

    private JsonNode callChatCompletion(Map<String, Object> requestBody) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String requestJson = objectMapper.writeValueAsString(requestBody);
        log.info("→ 调用 LLM: model={}", requestBody.get("model"));
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/v1/chat/completions", new HttpEntity<>(requestJson, headers), String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        if (root.has("error")) throw new IllegalStateException("LLM API 错误: " + root.get("error"));

        JsonNode choices = root.path("choices");
        JsonNode message = choices.get(0).path("message");

        if (message.has("tool_calls") && message.path("tool_calls").isArray()) {
            log.info("← LLM 响应: 调用 {} 个工具", message.path("tool_calls").size());
        } else {
            log.info("← LLM 响应: 直接回复");
        }
        return message;
    }

    private Map<String, Object> toAssistantMessage(JsonNode assistant) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", assistant.path("content").isNull() ? null : assistant.path("content").asText());
        msg.put("tool_calls", objectMapper.convertValue(assistant.path("tool_calls"), List.class));
        if (assistant.hasNonNull("reasoning_content")) {
            msg.put("reasoning_content", assistant.get("reasoning_content").asText());
        }
        return msg;
    }

    private String executeTool(String functionName, String arguments) {
        if (weatherTool.getToolName().equals(functionName)) return weatherTool.execute(functionName, arguments);
        if (dateTimeTool.getToolName().equals(functionName)) return dateTimeTool.execute(functionName, arguments);
        if (textToSpeechTool.getToolName().equals(functionName)) return textToSpeechTool.execute(functionName, arguments);
        if (geocodeTool.getToolName().equals(functionName)) return geocodeTool.execute(functionName, arguments);
        if (searchNearbyTool.getToolName().equals(functionName)) return searchNearbyTool.execute(functionName, arguments);
        if (planRouteTool.getToolName().equals(functionName)) return planRouteTool.execute(functionName, arguments);
        if (tarotTool.getToolName().equals(functionName)) return tarotTool.execute(functionName, arguments);
        if (remindTool.getToolName().equals(functionName)) return remindTool.execute(functionName, arguments);
        if (scheduledTaskTool.getToolName().equals(functionName)) return scheduledTaskTool.execute(functionName, arguments);
        if (imageGenerationTool.getToolName().equals(functionName)) return imageGenerationTool.execute(functionName, arguments);
        return "工具调用失败：未找到工具 " + functionName;
    }

    /**
     * 图片识别对话 — 使用 RestTemplate 调用 Vision API。
     */
    public String chatWithImage(String userId, byte[] imageBytes, String fileName) {
        log.info("========== 图片识别开始 ==========");
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mimeType = getMimeType(fileName);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text", "请详细描述这张图片的内容。用友好、简洁的中文回复，控制在200字以内。"));
        contentParts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));

        String today = java.time.LocalDate.now().toString();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT + "\n当前日期：" + today));
        messages.add(Map.of("role", "user", "content", contentParts));

        Map<String, Object> requestBody = Map.of("model", visionModel, "messages", messages, "max_tokens", 1024);

        String reply = callLlm(visionBaseUrl, visionApiKey, requestBody);

        chatMemory.add(userId, List.of(
                new UserMessage("[发送了一张图片]"),
                new AssistantMessage(reply)
        ));

        log.info("---------- 图片识别结果 ----------");
        log.info("回复内容: {}", reply.trim());
        log.info("========== 图片识别结束 ==========");
        return reply.trim();
    }

    private String callLlm(String apiUrl, String key, Map<String, Object> requestBody) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(key);
            String json = objectMapper.writeValueAsString(requestBody);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl + "/v1/chat/completions", new HttpEntity<>(json, headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("error")) throw new IllegalStateException("API 错误: " + root.get("error"));
            String content = root.path("choices").get(0).path("message").path("content").asText("").trim();
            return content.isEmpty() ? "抱歉，AI 服务暂时不可用。" : content;
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            return "抱歉，AI 服务暂时不可用。";
        }
    }

    /**
     * 去除 LLM 回复中重复的内容。
     *
     * <p>LLM 有时会将整段回复重复生成两遍（内容完全相同）。
     * 使用滑动窗口找到重复起点，若重复部分占总内容 40% 以上则去重。</p>
     */
    private String deduplicateReply(String reply) {
        if (reply == null || reply.isEmpty()) return reply;
        String[] lines = reply.split("\\n", -1);
        int total = lines.length;
        if (total < 4) return reply;

        // 从中点附近开始，寻找重复起点
        int searchEnd = Math.min(total - 1, total / 2 + 2);
        for (int split = total / 2; split <= searchEnd; split++) {
            int remaining = total - split;
            boolean match = true;
            // 比较 lines[0..remaining-1] 和 lines[split..split+remaining-1]
            for (int i = 0; i < remaining; i++) {
                if (!lines[i].equals(lines[split + i])) {
                    match = false;
                    break;
                }
            }
            if (match && remaining * 10 / total >= 4) {
                return String.join("\n", Arrays.copyOfRange(lines, 0, split)).trim();
            }
        }
        return reply;
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
