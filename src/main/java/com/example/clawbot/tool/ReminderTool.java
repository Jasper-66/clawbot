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
 * 定时提醒 Function Calling 工具，负责工具定义、参数解析和结果序列化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTool {

    private static final String NAME = "create_reminder";
    private static final int MAX_CONTENT_LENGTH = 200;

    private final ReminderService reminderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getToolName() {
        return NAME;
    }

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
