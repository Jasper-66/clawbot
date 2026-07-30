package com.example.clawbot.tool;

import com.example.clawbot.service.ReminderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

// 提醒工具：LLM 可调用创建一次性或周期性定时提醒
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTool {

    private final ReminderService reminderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_CONTENT_LENGTH = 200;

    @Tool(name = "create_reminder", description = "创建一次性定时提醒。当用户消息包含提醒意图且有明确时间（如'5分钟后提醒我'、'明天8点提醒我开会'）时，必须调用此工具。如果用户没有提供时间，先询问时间再调用。")
    public String createReminder(
            @ToolParam(description = "提醒内容，例如：带伞、喝水、参加会议") String content,
            @ToolParam(description = "提醒的绝对时间，必须是带时区的 ISO 8601 格式。如果是相对时间（如'5分钟后'），必须先计算出绝对时间再传入。示例：2026-07-30T10:30:00+08:00") String trigger_at,
            @ToolParam(required = false, description = "提醒方式，未指定时默认为 text，可选值：text、voice、both") String reminder_type,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        if (content == null || content.trim().isEmpty()) {
            return errorResult("提醒内容不能为空");
        }
        String trimmedContent = content.trim();
        if (trimmedContent.length() > MAX_CONTENT_LENGTH) {
            return errorResult("提醒内容不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
        }
        if (trigger_at == null || trigger_at.trim().isEmpty()) {
            return errorResult("提醒时间不能为空");
        }

        String effectiveUserId = (user_id == null || user_id.trim().isEmpty())
                ? "unknown_user"
                : user_id.trim();
        log.info("[行动] LLM调用工具: create_reminder(content=\"{}\", triggerAt=\"{}\") → 创建一次性定时提醒",
                trimmedContent, trigger_at);

        try {
            Instant triggerAt = OffsetDateTime.parse(trigger_at.trim()).toInstant();

            String typeText = (reminder_type == null || reminder_type.trim().isEmpty())
                    ? "text"
                    : reminder_type.trim().toLowerCase(Locale.ROOT);

            ReminderService.ReminderType reminderType;
            try {
                reminderType = ReminderService.ReminderType.valueOf(typeText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return errorResult("reminder_type 只能是 text、voice 或 both");
            }

            ReminderService.ReminderTask task = reminderService.createReminder(
                    effectiveUserId, trimmedContent, triggerAt, reminderType);

            log.info("[观察] 工具返回: create_reminder → 提醒已创建 (ID={}, 触发时间={})",
                    task.id(), trigger_at);
            return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "reminder_id", task.id(),
                    "content", task.content(),
                    "trigger_at", trigger_at.trim(),
                    "reminder_type", task.type().name().toLowerCase(Locale.ROOT)
            ));
        } catch (IllegalArgumentException e) {
            log.warn("[观察] 调用失败 create_reminder(userId=\"{}\", content=\"{}\"): 参数校验 - {}",
                    effectiveUserId, trimmedContent, e.getMessage());
            return errorResult(e.getMessage());
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("[观察] 调用失败 create_reminder(triggerAt=\"{}\"): 时间格式错误 - {}, 建议: LLM 应使用 ISO 8601 格式如 2026-07-23T08:00:00+08:00",
                    trigger_at, e.getMessage());
            return errorResult("时间格式不正确，请使用 ISO 8601 格式，例如：2026-07-23T08:00:00+08:00");
        } catch (Exception e) {
            log.error("[观察] 调用失败 create_reminder(userId=\"{}\", content=\"{}\"): 系统异常 - {}, 建议: 检查系统日志排查",
                    effectiveUserId, trimmedContent, e.getMessage(), e);
            return errorResult("参数或时间格式不正确");
        }
    }

    @Tool(name = "create_periodic_reminder", description = "创建周期性定时提醒，每隔指定时间重复提醒一次。当用户说「每X分钟/小时/天提醒我...」「定期提醒...」「每隔...」时调用此工具。")
    public String createPeriodicReminder(
            @ToolParam(description = "提醒内容，例如：该喝水了、站起来活动一下、记得吃药") String content,
            @ToolParam(description = "提醒间隔，单位秒。至少 60 秒（1 分钟）。例如：300 表示每 5 分钟，3600 表示每小时，86400 表示每天") long interval_seconds,
            @ToolParam(required = false, description = "首次提醒的带时区 ISO 8601 时间。不填则从当前时间开始计算间隔后首次触发") String trigger_at,
            @ToolParam(required = false, description = "提醒方式，未指定时默认为 text，可选值：text、voice、both") String reminder_type,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        if (content == null || content.trim().isEmpty()) {
            return errorResult("提醒内容不能为空");
        }
        String trimmedContent = content.trim();
        if (trimmedContent.length() > MAX_CONTENT_LENGTH) {
            return errorResult("提醒内容不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
        }
        if (interval_seconds < 60) {
            return errorResult("周期性提醒的间隔不能少于 60 秒（1 分钟）");
        }

        String typeText = (reminder_type == null || reminder_type.trim().isEmpty())
                ? "text"
                : reminder_type.trim().toLowerCase(Locale.ROOT);

        ReminderService.ReminderType reminderType;
        try {
            reminderType = ReminderService.ReminderType.valueOf(typeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return errorResult("reminder_type 只能是 text、voice 或 both");
        }

        String effectiveUserId = (user_id == null || user_id.trim().isEmpty())
                ? "unknown_user"
                : user_id.trim();
        log.info("[行动] LLM调用工具: create_periodic_reminder(content=\"{}\", interval={}s) → 创建周期性定时提醒",
                trimmedContent, interval_seconds);

        try {
            Instant firstTriggerAt;
            if (trigger_at != null && !trigger_at.trim().isEmpty()) {
                firstTriggerAt = OffsetDateTime.parse(trigger_at.trim()).toInstant();
                if (!firstTriggerAt.isAfter(Instant.now())) {
                    return errorResult("首次提醒时间必须晚于当前时间");
                }
            } else {
                // 未指定首次触发时间，从当前时间 + 间隔开始
                firstTriggerAt = Instant.now().plusSeconds(interval_seconds);
            }

            ReminderService.ReminderTask task = reminderService.createPeriodicReminder(
                    effectiveUserId, trimmedContent, firstTriggerAt, reminderType, interval_seconds);

            log.info("[观察] 工具返回: create_periodic_reminder → 周期性提醒已创建 (ID={}, 间隔{}s)",
                    task.id(), interval_seconds);
            return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "reminder_id", task.id(),
                    "content", task.content(),
                    "first_trigger_at", firstTriggerAt.toString(),
                    "interval_seconds", interval_seconds,
                    "reminder_type", task.type().name().toLowerCase(Locale.ROOT),
                    "periodic", true
            ));
        } catch (IllegalArgumentException e) {
            log.warn("[观察] 调用失败 create_periodic_reminder(userId=\"{}\", content=\"{}\", interval={}s): 参数校验 - {}",
                    effectiveUserId, trimmedContent, interval_seconds, e.getMessage());
            return errorResult(e.getMessage());
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("[观察] 调用失败 create_periodic_reminder(triggerAt=\"{}\"): 时间格式错误 - {}, 建议: LLM 应使用 ISO 8601 格式",
                    trigger_at, e.getMessage());
            return errorResult("时间格式不正确，请使用 ISO 8601 格式，例如：2026-07-23T08:00:00+08:00");
        } catch (Exception e) {
            log.error("[观察] 调用失败 create_periodic_reminder(userId=\"{}\", content=\"{}\", interval={}s): 系统异常 - {}, 建议: 检查系统日志排查",
                    effectiveUserId, trimmedContent, interval_seconds, e.getMessage(), e);
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
