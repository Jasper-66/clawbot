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
 * 提醒工具 — 纯提醒功能。
 *
 * <p>当用户要求「提醒我做某事」「查看提醒」「删除提醒」时使用。
 * 周期性提醒（每天/每周/每月）在此工具中处理。</p>
 *
 * <p>与 {@link ScheduledTaskTool} 的区别：</p>
 * <ul>
 *   <li>RemindTool — 纯提醒（到时间发提醒消息）</li>
 *   <li>ScheduledTaskTool — 延迟执行某个操作（到时间执行图片生成、天气查询等）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemindTool {

    private final ScheduledTaskRepository repository;
    private final com.example.clawbot.service.ScheduledTaskScheduler taskScheduler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "remind";

    public String getToolName() { return NAME; }

    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", "设置、查看或删除提醒。支持每天/每周/每月的周期性提醒和一次性提醒。当用户提到「提醒」「记得叫我」「别忘了」「每天」「每周」等意图时使用。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "action", Map.of(
                                                "type", "string",
                                                "description", "操作：create / list / delete",
                                                "enum", List.of("create", "list", "delete")
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "提醒内容，如「上班打卡」「吃药」「喝水」。create时必填。"
                                        ),
                                        "time_expr", Map.of(
                                                "type", "string",
                                                "description", "时间表达式，支持周期和一次性：「每天 8:50」「每周五 17:00」「每月1号 10:00」「明天 14:30」「3分钟后」。create时必填。"
                                        ),
                                        "remind_id", Map.of(
                                                "type", "integer",
                                                "description", "提醒ID，delete时必填。"
                                        )
                                ),
                                "required", List.of("action")
                        )
                )
        );
    }

    @Tool(name = "remind", description = "设置、查看或删除提醒。支持每天/每周/每月的周期性提醒和一次性提醒。当用户提到「提醒」「记得叫我」「别忘了」「每天」「每周」等意图时使用。")
    public String remind(
            @ToolParam(required = true, description = "操作：create/list/delete") String action,
            @ToolParam(required = false, description = "提醒内容") String content,
            @ToolParam(required = false, description = "时间表达式：每天 8:50 / 每周五 17:00 / 3分钟后") String timeExpr,
            @ToolParam(required = false, description = "提醒ID") Integer remindId) {

        String userId = RemindContextHolder.getUserId();
        if (userId == null) return "⚠️ 无法获取用户信息";

        try {
            return switch (action) {
                case "create" -> handleCreate(userId, content, timeExpr);
                case "list" -> handleList(userId);
                case "delete" -> handleDelete(userId, remindId);
                default -> "⚠️ 不支持的操作：" + action;
            };
        } catch (Exception e) {
            log.error("提醒工具执行失败: {}", e.getMessage(), e);
            return "⚠️ 操作失败：" + e.getMessage();
        }
    }

    public String execute(String functionName, String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            return remind(
                    args.path("action").asText(""),
                    args.path("content").asText(null),
                    args.path("time_expr").asText(null),
                    args.has("remind_id") ? args.path("remind_id").asInt() : null
            );
        } catch (Exception e) { return "⚠️ 参数解析失败：" + e.getMessage(); }
    }

    private String handleCreate(String userId, String content, String timeExpr) {
        if (content == null || content.isBlank()) return "⚠️ 请告诉我需要提醒什么";
        if (timeExpr == null || timeExpr.isBlank()) return "⚠️ 请告诉我什么时候提醒";

        ParsedTime parsed = parseTimeExpr(timeExpr.trim());
        if (parsed == null) return "⚠️ 无法理解时间「" + timeExpr + "」";

        ScheduledTask task = ScheduledTask.builder()
                .userId(userId)
                .taskType("REMIND")
                .content(content.trim())
                .executeAt(parsed.time)
                .repeatType(parsed.repeatType)
                .status("ACTIVE")
                .build();
        repository.save(task);
        taskScheduler.scheduleTask(task);

        String repeatDesc = switch (parsed.repeatType) {
            case "DAILY" -> "每天";
            case "WEEKLY" -> "每周";
            case "MONTHLY" -> "每月";
            default -> "一次性";
        };

        return String.format("✅ 提醒已设置！\n📝 内容：%s\n⏰ 时间：%s\n🔄 类型：%s\n🆔 ID：%d",
                content.trim(),
                parsed.time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                repeatDesc, task.getId());
    }

    private String handleList(String userId) {
        List<ScheduledTask> tasks = repository.findByUserIdAndStatusOrderByExecuteAtAsc(userId, "ACTIVE");
        List<ScheduledTask> reminds = tasks.stream()
                .filter(t -> "REMIND".equals(t.getTaskType()))
                .toList();
        if (reminds.isEmpty()) return "📋 你当前没有提醒";

        StringBuilder sb = new StringBuilder("📋 你的提醒列表：\n\n");
        for (ScheduledTask t : reminds) {
            String repeatDesc = switch (t.getRepeatType()) {
                case "DAILY" -> "每天";
                case "WEEKLY" -> "每周";
                case "MONTHLY" -> "每月";
                default -> "一次性";
            };
            sb.append(String.format("🔔 🆔%d | %s | %s | %s\n",
                    t.getId(),
                    t.getExecuteAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                    repeatDesc, t.getContent()));
        }
        sb.append("\n💡 说「删除提醒 ID」可删除");
        return sb.toString();
    }

    private String handleDelete(String userId, Integer remindId) {
        if (remindId == null) return "⚠️ 请告诉我要删除哪个提醒的 ID";
        var opt = repository.findByIdAndUserId((long) remindId, userId);
        if (opt.isEmpty()) return "⚠️ 未找到 ID 为 " + remindId + " 的提醒";

        ScheduledTask task = opt.get();
        task.setStatus("CANCELLED");
        repository.save(task);
        taskScheduler.cancelTask(task.getId());
        return "✅ 已删除提醒：" + task.getContent();
    }

    // ==================== 时间解析 ====================

    private record ParsedTime(LocalDateTime time, String repeatType) {}

    private ParsedTime parseTimeExpr(String expr) {
        LocalDateTime now = LocalDateTime.now();

        Matcher m = Pattern.compile("每天[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) return new ParsedTime(nextTime(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), "DAILY");

        m = Pattern.compile("每周([一二三四五六日天])[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) return new ParsedTime(nextWeekly(chineseDow(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), "WEEKLY");

        m = Pattern.compile("每月(\\d{1,2})[号日][\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) return new ParsedTime(nextMonthly(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), "MONTHLY");

        m = Pattern.compile("明天[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) return new ParsedTime(now.plusDays(1).withHour(Integer.parseInt(m.group(1))).withMinute(Integer.parseInt(m.group(2))).withSecond(0), "NONE");

        m = Pattern.compile("今天[\\s]*(\\d{1,2})[\\s:：时点](\\d{2})").matcher(expr);
        if (m.find()) {
            LocalDateTime t = now.withHour(Integer.parseInt(m.group(1))).withMinute(Integer.parseInt(m.group(2))).withSecond(0);
            return t.isBefore(now) ? null : new ParsedTime(t, "NONE");
        }

        m = Pattern.compile("([\\d一二三四五六七八九十百]+)(分钟|小时|天)后?").matcher(expr);
        if (m.find()) {
            long amount = parseChineseNumber(m.group(1));
            if (amount <= 0) return null;
            return new ParsedTime(switch (m.group(2)) {
                case "分钟" -> now.plusMinutes(amount);
                case "小时" -> now.plusHours(amount);
                case "天" -> now.plusDays(amount);
                default -> now.plusMinutes(amount);
            }, "NONE");
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
        long current = 0;
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
        return current > 0 ? current : -1;
    }
}
