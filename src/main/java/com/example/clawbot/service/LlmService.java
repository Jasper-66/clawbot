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

/**
 * 大语言模型（LLM）对话服务 — 系统的"大脑"。
 *
 * <p>提供两大核心能力：<b>文本对话</b>（含 Function Calling）和 <b>图片识别</b>（多模态 Vision）。
 * 通过 DeepSeek Chat API 进行文本对话，DashScope Vision API 进行图片分析。</p>
 *
 * <h3>对话管理</h3>
 * <p>每个用户（以 userId 区分）独立维护一个 {@link LinkedList} 对话历史，
 * 最多保存最近 {@value #MAX_HISTORY} 轮（一问一答为一轮）。
 * 使用 {@link ConcurrentHashMap} 存储所有用户的对话，线程安全。</p>
 *
 * <h3>Function Calling 流程</h3>
 * <ol>
 *   <li>请求中注册全部 5 个工具定义（天气、地理编码、周边搜索、路线规划、语音合成）</li>
 *   <li>设置 {@code tool_choice: "auto"} — 模型自行决定是否调用工具</li>
 *   <li>如模型返回 {@code tool_calls}，解析每个工具的名称和参数 JSON</li>
 *   <li>路由到对应 Tool 执行，将结果以 {@code role: "tool"} 消息回传</li>
 *   <li>重复以上过程，直到模型返回文本内容或达到最大轮数 {@value #MAX_TOOL_ROUNDS}</li>
 * </ol>
 *
 * <h3>工具注册架构</h3>
 * <pre>
 * LLM 请求中的 tools 数组
 *   ├── weatherTool.getToolDefinition()      → get_weather
 *   ├── geocodeTool.getToolDefinition()      → geocode
 *   ├── searchNearbyTool.getToolDefinition() → search_nearby
 *   ├── planRouteTool.getToolDefinition()    → plan_route
 *   └── textToSpeechTool.getToolDefinition() → text_to_speech
 * </pre>
 *
 * @see com.example.clawbot.tool.WeatherTool
 * @see com.example.clawbot.tool.GeocodeTool
 * @see com.example.clawbot.tool.SearchNearbyTool
 * @see com.example.clawbot.tool.PlanRouteTool
 * @see com.example.clawbot.tool.TextToSpeechTool
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    /** HTTP 客户端，统一用于所有外部 API 调用 */
    private final RestTemplate restTemplate;

    /** 天气查询工具 — 调用心知天气 API */
    private final WeatherTool weatherTool;

    /** 地理编码工具 — 地址转经纬度（高德地图 API） */
    private final GeocodeTool geocodeTool;

    /** 周边搜索工具 — 搜索附近 POI（高德地图 API） */
    private final SearchNearbyTool searchNearbyTool;

    /** 路线规划工具 — 驾车路线计算（高德地图 API） */
    private final PlanRouteTool planRouteTool;

    /** 语音合成工具 — 文字转 WAV 音频（DashScope TTS） */
    private final TextToSpeechTool textToSpeechTool;

    /** Jackson JSON 解析器，线程安全 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 用户对话历史缓存。
     *
     * <p>Key 为用户唯一标识（微信 userId），Value 为该用户的对话消息列表。
     * LinkedList 保证有序，支持高效的头部删除（淘汰旧消息）。</p>
     */
    private final ConcurrentHashMap<String, LinkedList<Map<String, Object>>> conversations = new ConcurrentHashMap<>();

    /** 每个用户最多保留的对话轮数（一问一答 = 2 条消息） */
    private static final int MAX_HISTORY = 10;

    /** Function Calling 最大工具调用轮数，防止无限循环（如模型反复调用同一工具） */
    private static final int MAX_TOOL_ROUNDS = 30;

    /** DeepSeek API 密钥 */
    @Value("${deepseek.api.key}")
    private String apiKey;

    /** DeepSeek API 基础地址（如 https://api.deepseek.com） */
    @Value("${deepseek.api.base-url}")
    private String baseUrl;

    /** DeepSeek 对话模型名称（如 deepseek-chat） */
    @Value("${deepseek.api.model}")
    private String model;

    /** DashScope Vision API 密钥（与语音服务共用） */
    @Value("${vision.api.key}")
    private String visionApiKey;

    /** DashScope Vision API 基础地址 */
    @Value("${vision.api.base-url}")
    private String visionBaseUrl;

    /** 多模态 Vision 模型名称（如 qwen-vl-plus） */
    @Value("${vision.api.model}")
    private String visionModel;

    /**
     * 系统提示词（System Prompt），定义机器人的行为准则。
     *
     * <p>关键指令：</p>
     * <ul>
     *   <li>用简洁自然的中文回复，控制在 200 字以内</li>
     *   <li>调用 text_to_speech 工具后，必须在回复中保留 {@code [audio:文件路径]} 标记，
     *       供 WeChatBotService 识别并发送语音消息</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT =
            "你是一个友好的微信助手，请用简洁、自然的中文回答用户的问题。回答尽量控制在200字以内。\n"
                    + "如果调用了 text_to_speech 工具生成了语音，请务必在回复中保留 [audio:工具返回的file_path] 标记，以便系统发送给用户语音消息。";

    /**
     * 文本对话入口（面向 WeChatBotService 的主接口）。
     *
     * <p>完整的对话处理流程：</p>
     * <ol>
     *   <li>从 {@code conversations} Map 获取或创建该用户的对话历史（{@link LinkedList}）</li>
     *   <li>组装消息列表：系统提示词 → 历史消息（最多 {@value #MAX_HISTORY} 轮）→ 当前用户消息</li>
     *   <li>在请求中注册全部 5 个工具定义，设置 {@code tool_choice: "auto"} 让模型自行决定是否调用</li>
     *   <li>调用 {@link #callLlmWithTools} 进入 Function Calling 闭环</li>
     *   <li>将本轮用户消息和助手最终回复追加到历史，超出上限则淘汰最早的一轮</li>
     * </ol>
     *
     * <p><b>线程安全</b>：对单用户历史列表的操作通过 {@code synchronized} 块保护，
     * 避免同一用户并发请求导致历史数据错乱或 {@link java.util.ConcurrentModificationException}。</p>
     *
     * @param userId      用户唯一标识（微信的 from_user_id）
     * @param userMessage 用户发送的原始文本消息
     * @return 助手回复文本，可直接发送给用户；异常时返回友好的错误提示而非抛异常
     */
    public String chat(String userId, String userMessage) {
        // 获取或创建该用户的对话历史列表
        LinkedList<Map<String, Object>> history = conversations.computeIfAbsent(userId, k -> new LinkedList<>());

        // 组装消息：system prompt → 历史消息 → 当前用户消息
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // 构建请求体：注册工具 + 设置参数
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
        requestBody.put("tool_choice", "auto"); // 让模型自行决定是否调用

        // 进入 Function Calling 闭环
        String reply = callLlmWithTools(requestBody, messages);

        // 更新对话历史（线程安全），淘汰旧消息
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
     * Function Calling 闭环处理 — ReAct 循环的简化实现。
     *
     * <p>这是 LLM 工具调用的核心循环逻辑：</p>
     * <ol>
     *   <li>调用 Chat Completion API，获取助手响应</li>
     *   <li>检查响应的 {@code finish_reason}：
     *     <ul>
     *       <li>{@code stop} — 模型已完成回答，提取 {@code content} 文本返回给用户</li>
     *       <li>{@code tool_calls} — 模型决定调用一个或多个工具，继续下一步</li>
     *     </ul>
     *   </li>
     *   <li>遍历 {@code tool_calls} 数组，解析每个工具的名称（name）和参数（arguments）</li>
     *   <li>调用 {@link #executeTool} 执行对应工具，获取结果</li>
     *   <li>将助手工具调用消息（role=assistant, tool_calls）和各工具结果（role=tool）追加到 messages</li>
     *   <li>回到步骤 1，模型看到工具结果后决定是继续调用工具还是给出最终回答</li>
     * </ol>
     *
     * <h3>安全保护</h3>
     * <p>最多执行 {@value #MAX_TOOL_ROUNDS} 轮工具调用。如果模型陷入循环
     * （例如反复调用同一工具），达到上限后返回友好提示而非死循环。</p>
     *
     * <h3>DeepSeek 思考模式兼容</h3>
     * <p>DeepSeek R1 等推理模型在工具调用消息中包含 {@code reasoning_content} 字段，
     * 必须通过 {@link #toAssistantToolCallMessage} 原样保留并回传，
     * 否则 API 会返回 400 错误。</p>
     *
     * @param requestBody 请求体 Map — 在此方法内被更新以反映最新消息状态
     * @param messages    消息列表 — 被追加工具调用消息和工具结果消息
     * @return 模型最终的文本回复；超轮数或异常时返回兜底提示
     */
    private String callLlmWithTools(Map<String, Object> requestBody,
                                    List<Map<String, Object>> messages) {
        try {
            for (int toolRound = 0; toolRound <= MAX_TOOL_ROUNDS; toolRound++) {
                // 1. 调用 LLM
                JsonNode assistant = callChatCompletion(baseUrl, apiKey, requestBody);
                JsonNode toolCalls = assistant.path("tool_calls");

                // 2. 无工具调用 → 模型已完成回答
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    String content = assistant.path("content").asText("").trim();
                    return content.isEmpty() ? "抱歉，我没有生成有效回复，请稍后再试。" : content;
                }

                // 3. 达到最大轮数 → 终止循环
                if (toolRound == MAX_TOOL_ROUNDS) {
                    return "抱歉，工具调用次数过多，请换一种方式提问。";
                }

                // 4. 将助手的工具调用消息加入消息列表
                messages.add(toAssistantToolCallMessage(assistant));

                // 5. 逐个执行工具并将结果回传
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

    /**
     * 根据 LLM 返回的工具名称路由到对应的 Tool 组件执行。
     *
     * <p>使用显式的 if-else 链而非反射/Map 路由，原因是：
     * 工具数量固定（5 个），if-else 链代码清晰、IDE 可追踪引用、
     * 无需额外的注册机制。如需新增工具，在此方法中添加一个 if 分支即可。</p>
     *
     * <p>每个 Tool 各自负责参数校验和异常处理，返回结果可以是
     * 纯文本（如天气描述）或 JSON 字符串（如地理编码坐标）。</p>
     *
     * @param functionName LLM 返回的工具名称（如 "get_weather"、"geocode"）
     * @param arguments    工具参数 JSON 字符串（如 {@code {"city":"北京"}}）
     * @return 工具执行结果字符串，找不到工具时返回错误说明
     */
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

    /**
     * 将 DeepSeek API 返回的助手消息 JSON 节点转换为标准消息 Map。
     *
     * <p>返回格式符合 OpenAI Chat Completion API 规范：
     * {@code {role: "assistant", content: "..." | null, tool_calls: [...]}}</p>
     *
     * <p><b>DeepSeek 思考模式（Reasoning Mode）兼容性</b>：</p>
     * <p>DeepSeek R1 等推理模型在调用工具时，消息中包含 {@code reasoning_content}
     * （模型的内部推理过程）。后续请求<b>必须</b>原样带回此字段，
     * 否则 API 会返回以下错误：</p>
     * <pre>400 - "When using the deepseek-reasoner model, tool call messages
     * must retain the reasoning_content field"</pre>
     *
     * @param assistant DeepSeek API 响应中 {@code choices[0].message} 的 Jackson JsonNode
     * @return 标准格式的助手消息 Map（role、content、tool_calls，可选 reasoning_content）
     */
    private Map<String, Object> toAssistantToolCallMessage(JsonNode assistant) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", assistant.path("content").isNull()
                ? null : assistant.path("content").asText());
        message.put("tool_calls", objectMapper.convertValue(assistant.path("tool_calls"), List.class));

        // DeepSeek R1 推理模型要求保留 reasoning_content，否则后续请求报 400
        if (assistant.hasNonNull("reasoning_content")) {
            message.put("reasoning_content", assistant.get("reasoning_content").asText());
        }
        return message;
    }

    /**
     * 调用 LLM Chat Completion API — 底层 HTTP 通信。
     *
     * <p>发送 POST 请求到 {@code {baseUrl}/v1/chat/completions}，使用 Bearer Token 认证。
     * 请求体和响应体均通过 Jackson 处理。</p>
     *
     * <h3>响应校验链</h3>
     * <ol>
     *   <li>响应体不为 {@code null}</li>
     *   <li>不存在 {@code error} 字段（如 API key 无效、模型不存在等）</li>
     *   <li>{@code choices} 数组非空且包含 {@code message} 节点</li>
     * </ol>
     *
     * <p>此方法不捕获异常 — 由上层（{@link #callLlmWithTools} 或 {@link #callLlm}）处理。</p>
     *
     * @param apiUrl      API 基础地址（如 {@code https://api.deepseek.com}）
     * @param key         API 密钥，作为 Bearer Token 发送
     * @param requestBody 完整的请求体 Map（model、messages、tools、temperature 等），由 Jackson 序列化
     * @return 响应中 {@code choices[0].message} 节点（包含 role、content、可能包含 tool_calls）
     * @throws Exception 请求失败、响应校验不通过或 JSON 解析异常时抛出
     */
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

    /**
     * 图片识别对话（多模态 Vision API）。
     *
     * <p>将微信图片转为 Base64 Data URL（{@code data:image/{mime};base64,...}），
     * 与文本提示词一起发送至多模态 Vision API 进行分析。</p>
     *
     * <h3>消息格式</h3>
     * <p>图片识别使用 OpenAI Vision API 兼容的 Content Array 格式：</p>
     * <pre>{@code
     * "content": [
     *   {"type": "text", "text": "请详细描述..."},
     *   {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
     * ]
     * }</pre>
     *
     * <h3>对话历史集成</h3>
     * <p>识别结果会追加到用户对话历史（图片消息占位为 {@code [发送了一张图片]}），
     * 使后续文本对话可以引用之前的图片内容（"刚才那张图里的..."）。</p>
     *
     * @param userId     用户唯一标识
     * @param imageBytes 图片原始字节（由 WeChatBotService 从微信下载）
     * @param fileName   文件名，用于推断 MIME 类型（如 "photo.jpg" → image/jpeg）
     * @return 图片内容的中文描述文本，或错误提示
     */
    public String chatWithImage(String userId, byte[] imageBytes, String fileName) {
        // 构建 Base64 Data URL
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mimeType = getMimeType(fileName);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        // 构建多模态消息：文本提示 + 图片 Data URL
        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text",
                "请详细描述这张图片的内容。用友好、简洁的中文回复，控制在200字以内。"));
        contentParts.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUrl)
        ));

        // 组装完整消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        LinkedList<Map<String, Object>> history = conversations.get(userId);
        if (history != null) {
            synchronized (history) {
                messages.addAll(history);
            }
        }
        messages.add(Map.of("role", "user", "content", contentParts));

        // Vision API 不需要工具调用
        Map<String, Object> requestBody = Map.of(
                "model", visionModel,
                "messages", messages,
                "max_tokens", 1024
        );

        String reply = callLlm(visionBaseUrl, visionApiKey, requestBody);

        // 更新对话历史
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

    /**
     * 简化的 LLM 调用 — 无工具注册、无对话历史管理。
     *
     * <p>对应不需要 Function Calling 的场景（如图片识别、文档摘要），
     * 直接调用 {@link #callChatCompletion} 然后提取 {@code content} 字段。</p>
     *
     * <p>与 {@link #chat} 的区别：不注册 tools、不传 tool_choice、
     * 不处理 tool_calls、不管理对话历史。</p>
     *
     * @param apiUrl      API 基础地址
     * @param key         API 密钥
     * @param requestBody 请求体 Map（已包含 model 和 messages）
     * @return 模型回复的文本内容，异常时返回友好错误提示
     */
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

    /**
     * 根据文件扩展名推断图片 MIME 类型，用于构建 Data URL。
     *
     * <p>支持的图片格式及对应 MIME 类型：</p>
     * <ul>
     *   <li>{@code .jpg / .jpeg} → {@code image/jpeg}</li>
     *   <li>{@code .png} → {@code image/png}</li>
     *   <li>{@code .gif} → {@code image/gif}</li>
     *   <li>{@code .webp} → {@code image/webp}</li>
     *   <li>{@code .bmp} → {@code image/bmp}</li>
     * </ul>
     *
     * <p>无法识别的扩展名或文件名为 {@code null} 时，默认返回 {@code image/png}
     * （最通用的无损图片格式，兼容性最好）。</p>
     *
     * @param fileName 文件名，可能为 {@code null}
     * @return MIME 类型字符串（如 {@code "image/jpeg"}）
     */
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
