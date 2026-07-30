package com.example.clawbot.tool;

import com.example.clawbot.entity.ScheduledTask;
import com.example.clawbot.repository.ScheduledTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时任务工具 — 延迟执行任意操作。
 *
 * <p>当用户要求「N分钟后做某事」「明天做某事」时，LLM 调用此工具创建定时任务。
 * 支持的任务类型：延迟图片生成、延迟天气查询、延迟自定义消息等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskTool {

    private final ScheduledTaskRepository repository;
    private final com.example.clawbot.service.ScheduledTaskScheduler taskScheduler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "schedule_task";

    public String getToolName() { return NAME; }

    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", "创建定时/延迟任务。当用户要求在指定时间后执行某个操作时使用，例如「两分钟后生成小狗图片」「明天告诉我天气」「十分钟后提醒我喝水」。注意：纯提醒（不涉及其他操作）请使用 remind 工具。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "action", Map.of(
                                                "type", "string",
                                                "description", "操作：create（创建定时任务）/ list（查看任务）/ delete（删除任务）",
                                                "enum", List.of("create", "list", "delete")
                                        ),
                                        "task_type", Map.of(
                                                "type", "string",
                                                "description", "任务类型：IMAGE（定时生成图片）/ WEATHER（定时查天气）/ REMIND（定时提醒）/ CUSTOM（定时发送消息）",
                                                "enum", List.of("IMAGE", "WEATHER", "REMIND", "CUSTOM")
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "任务内容。IMAGE类型填图片描述，WEATHER类型填城市名，REMIND类型填提醒文本，CUSTOM类型填要发送的消息。create时必填。"
                                        ),
                                        "time_expr", Map.of(
                                                "type", "string",
                                                "description", "执行时间表达式：「3分钟后」「明天 9:00」「每天 8:50」「每周五 17:00」。create时必填。"
                                        ),
                                        "task_id", Map.of(
                                                "type", "integer",
                                                "description", "任务ID，delete时必填。"
                                        )
                                ),
                                "required", List.of("action")
                        )
                )
        );
    }

    @Tool(name = "schedule_task", description = "创建定时/延迟任务。当用户要求在指定时间后执行某个操作时使用，例如「两分钟后生成小狗图片」「明天告诉我天气」。")
    public String scheduleTask(
            @ToolParam(required = true, description = "操作：create/list/delete") String action,
            @ToolParam(required = false, description = "任务类型：IMAGE/WEATHER/REMIND/CUSTOM") String taskType,
            @ToolParam(required = false, description = "任务内容") String content,
            @ToolParam(required = false, description = "时间表达式：3分钟后 / 明天 9:00 / 每天 8:50") String timeExpr,
            @ToolParam(required = false, description = "任务ID") Integer taskId) {

        String userId = RemindContextHolder.getUserId();
        if (userId == null) return "⚠️ 无法获取用户信息";

        try {
            return switch (action) {
                case "create" -> handleCreate(userId, taskType, content, timeExpr);
                case "list" -> handleList(userId);
                case "delete" -> handleDelete(userId, taskId);
                default -> "⚠️ 不支持的操作：" + action;
            };
        } catch (Exception e) {
            log.error("定时任务工具执行失败: {}", e.getMessage(), e);
            return "⚠️ 操作失败：" + e.getMessage();
        }
    }

    public String execute(String functionName, String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            return scheduleTask(
                    args.path("action").asText(""),
                    args.path("task_type").asText(null),
                    args.path("content").asText(null),
                    args.path("time_expr").asText(null),
                    args.has("task_id") ? args.path("task_id").asInt() : null
            );
        } catch (Exception e) {
            return "⚠️ 参数解析失败：" + e.getMessage();
        }
    }

    private String handleCreate(String userId, String taskType, String content, String timeExpr) {
        if (taskType == null || taskType.isBlank()) return "⚠️ 请指定任务类型";
        if (content == null || content.isBlank()) return "⚠️ 请告诉我任务内容";
        if (timeExpr == null || timeExpr.isBlank()) return "⚠️ 请告诉我执行时间";

        ParsedTime parsed = parseTimeExpr(timeExpr.trim());
        if (parsed == null) return "⚠️ 无法理解时间「" + timeExpr + "」";

        ScheduledTask task = ScheduledTask.builder()
                .userId(userId)
                .taskType(taskType.toUpperCase())
                .content(content.trim())
                .executeAt(parsed.time)
                .repeatType(parsed.repeatType)
                .status("ACTIVE")
                .build();
        repository.save(task);
        taskScheduler.scheduleTask(task);

        String typeDesc = switch (taskType.toUpperCase()) {
            case "IMAGE" -> "🖼️ 定时图片生成";
            case "WEATHER" -> "🌤️ 定时天气查询";
            case "REMIND" -> "🔔 定时提醒";
            case "CUSTOM" -> "📌 定时消息";
            default -> "📋 定时任务";
        };
        String repeatDesc = switch (parsed.repeatType) {
            case "DAILY" -> "每天";
            case "WEEKLY" -> "每周";
            case "MONTHLY" -> "每月";
            default -> "一次性";
        };

        return String.format("✅ 定时任务已创建！\n%s\n📝 内容：%s\n⏰ 时间：%s\n🔄 类型：%s\n🆔 ID：%d",
                typeDesc, content.trim(),
                parsed.time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                repeatDesc, task.getId());
    }

    private String handleList(String userId) {
        List<ScheduledTask> tasks = repository.findByUserIdAndStatusOrderByExecuteAtAsc(userId, "ACTIVE");
        if (tasks.isEmpty()) return "📋 你当前没有定时任务";

        StringBuilder sb = new StringBuilder("📋 你的定时任务：\n\n");
        for (ScheduledTask t : tasks) {
            String typeIcon = switch (t.getTaskType()) {
                case "IMAGE" -> "🖼️";
                case "WEATHER" -> "🌤️";
                case "REMIND" -> "🔔";
                default -> "📌";
            };
            String repeatDesc = switch (t.getRepeatType()) {
                case "DAILY" -> "每天";
                case "WEEKLY" -> "每周";
                case "MONTHLY" -> "每月";
                default -> "一次性";
            };
            sb.append(String.format("%s 🆔%d | %s | %s | %s\n",
                    typeIcon, t.getId(),
                    t.getExecuteAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                    repeatDesc, t.getContent()));
        }
        return sb.toString();
    }

    private String handleDelete(String userId, Integer taskId) {
        if (taskId == null) return "⚠️ 请告诉我要删除哪个任务的 ID";
        var opt = repository.findByIdAndUserId((long) taskId, userId);
        if (opt.isEmpty()) return "⚠️ 未找到 ID 为 " + taskId + " 的任务";

        ScheduledTask task = opt.get();
        task.setStatus("CANCELLED");
        repository.save(task);
        taskScheduler.cancelTask(task.getId());
        return "✅ 已删除定时任务：" + task.getContent();
    }

    // ==================== 时间解析 ====================

    private record ParsedTime(LocalDateTime time, String repeatType) {}

    private ParsedTime parseTimeExpr(String expr) {
        LocalDateTime now = LocalDateTime.now();

        // 每天 HH:MM
        Matcher m = Pattern.compile("每天[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            return new ParsedTime(nextTime(h, min), "DAILY");
        }

        // 每周X HH:MM
        m = Pattern.compile("每周([一二三四五六日天])[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) {
            int dow = chineseDow(m.group(1)), h = Integer.parseInt(m.group(2)), min = Integer.parseInt(m.group(3));
            return new ParsedTime(nextWeekly(dow, h, min), "WEEKLY");
        }

        // 每月N号 HH:MM
        m = Pattern.compile("每月(\\d{1,2})[号日][\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2)), min = Integer.parseInt(m.group(3));
            return new ParsedTime(nextMonthly(day, h, min), "MONTHLY");
        }

        // 明天 HH:MM
        m = Pattern.compile("明天[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) {
            return new ParsedTime(now.plusDays(1).withHour(Integer.parseInt(m.group(1))).withMinute(Integer.parseInt(m.group(2))).withSecond(0), "NONE");
        }

        // 今天 HH:MM
        m = Pattern.compile("今天[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) {
            LocalDateTime t = now.withHour(Integer.parseInt(m.group(1))).withMinute(Integer.parseInt(m.group(2))).withSecond(0);
            if (t.isBefore(now)) return null;
            return new ParsedTime(t, "NONE");
        }

        // N分钟/小时/天后
        m = Pattern.compile("([\\d一二三四五六七八九十百]+)(分钟|小时|天)后?").matcher(expr);
        if (m.find()) {
            long amount = parseChineseNumber(m.group(1));
            if (amount <= 0) return null;
            LocalDateTime t = switch (m.group(2)) {
                case "分钟" -> now.plusMinutes(amount);
                case "小时" -> now.plusHours(amount);
                case "天" -> now.plusDays(amount);
                default -> now.plusMinutes(amount);
            };
            return new ParsedTime(t, "NONE");
        }

        return null;
    }

    private LocalDateTime nextTime(int h, int min) {
        LocalDateTime t = LocalDateTime.now().withHour(h).withMinute(min).withSecond(0);
        return t.isBefore(LocalDateTime.now()) ? t.plusDays(1) : t;
    }

    private LocalDateTime nextWeekly(int dow, int h, int min) {
        var today = java.time.LocalDate.now();
        int daysUntil = dow - today.getDayOfWeek().getValue();
        if (daysUntil <= 0) daysUntil += 7;
        return today.plusDays(daysUntil).atTime(h, min);
    }

    private LocalDateTime nextMonthly(int day, int h, int min) {
        var date = java.time.LocalDate.now();
        try { date = date.withDayOfMonth(day); } catch (Exception e) { date = date.withDayOfMonth(date.lengthOfMonth()); }
        LocalDateTime t = date.atTime(h, min);
        if (t.isBefore(LocalDateTime.now())) {
            date = date.plusMonths(1);
            try { date = date.withDayOfMonth(day); } catch (Exception e) { date = date.withDayOfMonth(date.lengthOfMonth()); }
            t = date.atTime(h, min);
        }
        return t;
    }

    private int chineseDow(String ch) {
        return switch (ch) {
            case "一" -> 1; case "二" -> 2; case "三" -> 3; case "四" -> 4;
            case "五" -> 5; case "六" -> 6; default -> 7;
        };
    }

    private long parseChineseNumber(String s) {
        try { return Long.parseLong(s); } catch (Exception ignored) {}
        long result = 0, current = 0;
        for (char c : s.toCharArray()) {
            long val = switch (c) {
                case '零' -> 0; case '一','壹' -> 1; case '二','两','贰' -> 2;
                case '三','叁' -> 3; case '四','肆' -> 4; case '五','伍' -> 5;
                case '六','陆' -> 6; case '七','柒' -> 7; case '八','捌' -> 8;
                case '九','玖' -> 9; case '十','拾' -> 10; case '百' -> 100;
                default -> -1;
            };
            if (val == -1) return -1;
            if (val >= 10) current = (current == 0 ? 1 : current) * val;
            else current = val;
        }
        return (result + current) > 0 ? result + current : -1;
    }
}
