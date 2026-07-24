package com.example.clawbot.tool;

import com.example.clawbot.service.ReminderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 定时提醒 Function Calling 工具。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code create_reminder}。
 * 当用户表达"提醒我 xx 时间做 xx"的意图时，LLM 调用此工具创建一次性定时提醒。</p>
 *
 * <h3>交互流程</h3>
 * <pre>
 * 用户："明天早上8点提醒我带伞"
 *   → LLM 检测到提醒意图
 *   → LLM 调用 create_reminder(content="带伞", trigger_at="2026-07-24T08:00:00+08:00")
 *   → ReminderTool 校验参数 → 委托 ReminderService.createReminder()
 *   → 返回 {"success": true, "reminder_id": "xxx", ...}
 * </pre>
 *
 * <h3>参数校验</h3>
 * <ul>
 *   <li>content — 非空，长度 ≤ {@value #MAX_CONTENT_LENGTH} 字符</li>
 *   <li>trigger_at — 非空，ISO 8601 格式（如 2026-07-23T08:00:00+08:00）</li>
 *   <li>reminder_type — 可选，默认为 "text"，仅接受 text/voice/both</li>
 * </ul>
 *
 * <h3>安全约束</h3>
 * <p>工具描述中明确要求 LLM：用户未提供明确时间时必须先询问，不可自行推测。
 * 防止 LLM 编造时间导致误导。</p>
 *
 * @see com.example.clawbot.service.ReminderService

 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTool {

    /** 工具名称，对应 LLM Function Calling 的 function.name */
    private static final String NAME = "create_reminder";

    /** 提醒内容最大长度限制，防止恶意超长内容 */
    private static final int MAX_CONTENT_LENGTH = 200;

    /** 提醒业务服务 — 实际执行提醒创建和存储 */
    private final ReminderService reminderService;

    /** Jackson JSON 解析器，用于解析参数和序列化结果 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "create_reminder"
     */
    public String getToolName() {
        return NAME;
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>{@code content}（必填）— 提醒内容文本，如 "带伞"、"喝水"等</li>
     *   <li>{@code trigger_at}（必填）— ISO 8601 带时区时间，LLM 需将用户自然语言转换为标准格式</li>
     *   <li>{@code reminder_type}（可选）— 提醒方式：text（文本）、voice（语音）、both（文本+语音）</li>
     * </ul>
     *
     * <p>{@code additionalProperties: false} 防止 LLM 传入额外参数引发未知错误。</p>
     *
     * @return Function Calling 格式的工具定义 Map
     */
    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", "创建一次性定时提醒。用户没有提供明确的提醒时间时，应先询问用户，不要调用工具。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "提醒内容，例如：带伞、喝水、参加会议"
                                        ),
                                        "trigger_at", Map.of(
                                                "type", "string",
                                                "description", "带时区的 ISO 8601 时间，例如：2026-07-23T08:00:00+08:00"
                                        ),
                                        "reminder_type", Map.of(
                                                "type", "string",
                                                "description", "提醒方式，未指定时默认为 text",
                                                "enum", List.of("text", "voice", "both")
                                        )
                                ),
                                "required", List.of("content", "trigger_at"),
                                "additionalProperties", false
                        )
                )
        );
    }

    /**
     * 校验并执行 LLM 请求的工具调用。
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>校验 functionName 是否匹配</li>
     *   <li>解析参数 JSON，提取 content / trigger_at / reminder_type</li>
     *   <li>参数校验：非空、长度限制</li>
     *   <li>解析 ISO 8601 时间为 {@link Instant}（OffsetDateTime.parse 支持时区）</li>
     *   <li>校验 reminder_type 枚举值</li>
     *   <li>委托 {@link ReminderService#createReminder} 执行实际创建</li>
     *   <li>返回包含 success、reminder_id 等信息的 JSON</li>
     * </ol>
     *
     * <p>异常处理策略：</p>
     * <ul>
     *   <li>{@link IllegalArgumentException} — ReminderService 校验不通过，
     *       返回其原始错误消息（如"提醒时间必须晚于当前时间"）</li>
     *   <li>其他异常 — 统一返回"参数或时间格式不正确"，避免暴露内部错误细节</li>
     * </ul>
     *
     * @param functionName  工具名称（应为 "create_reminder"）
     * @param argumentsJson LLM 生成的参数 JSON
     * @param userId        当前微信用户 ID，用于创建提醒时指定收信人
     * @return 执行结果 JSON — 成功包含 reminder_id，失败包含 success=false 和 message
     */
    public String execute(String functionName, String argumentsJson, String userId) {
        if (!NAME.equals(functionName)) {
            return errorResult("不支持的工具 " + functionName);
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String content = arguments.path("content").asText("").trim();
            String triggerAtText = arguments.path("trigger_at").asText("").trim();
            String typeText = arguments.path("reminder_type").asText("text").trim();

            if (content.isEmpty()) {
                return errorResult("提醒内容不能为空");
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                return errorResult("提醒内容不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
            }
            if (triggerAtText.isEmpty()) {
                return errorResult("提醒时间不能为空");
            }

            Instant triggerAt = OffsetDateTime.parse(triggerAtText).toInstant();
            ReminderService.ReminderType reminderType;
            try {
                reminderType = ReminderService.ReminderType.valueOf(
                        typeText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return errorResult("reminder_type 只能是 text、voice 或 both");
            }

            ReminderService.ReminderTask task = reminderService.createReminder(
                    userId, content, triggerAt, reminderType);

            return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "reminder_id", task.id(),
                    "content", task.content(),
                    "trigger_at", triggerAtText,
                    "reminder_type", task.type().name().toLowerCase(Locale.ROOT)
            ));
        } catch (IllegalArgumentException e) {
            return errorResult(e.getMessage());
        } catch (Exception e) {
            log.warn("创建提醒工具执行失败: {}", e.getMessage());
            return errorResult("参数或时间格式不正确");
        }
    }

    /**
     * 构建统一的失败响应 JSON。
     *
     * <p>使用 {@code success: false} 标记错误状态，方便 LLM 判断是否调用成功。
     * JSON 序列化失败时（极罕见情况），直接返回硬编码的错误 JSON 字符串作为兜底。</p>
     *
     * @param message 面向用户的错误描述
     * @return 格式化的 JSON 错误响应
     */
    private String errorResult(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "message", message
            ));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"创建提醒失败\"}";
        }
    }
}
