package com.example.clawbot.tool;

import com.example.clawbot.entity.ScheduledTaskEntity;
import com.example.clawbot.service.SchedulerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 周期任务管理工具 — Function Calling 工具。
 *
 * <p>支持创建、取消、列出周期性定时任务。
 * 当用户说"每X分钟做某事"、"取消定时任务"、"查看我的任务"时使用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskTool {

    private final SchedulerService schedulerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "manage_scheduled_task";
    private static final String DESCRIPTION = "管理周期性定时任务。支持三种操作：\n" +
            "- create：创建周期任务，如'每5分钟查一次微博热搜'\n" +
            "- cancel：取消指定任务或所有任务\n" +
            "- list：列出当前所有活跃的定时任务";

    public String getToolName() {
        return NAME;
    }

    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "action", Map.of(
                                                "type", "string",
                                                "description", "操作类型",
                                                "enum", List.of("create", "cancel", "list")
                                        ),
                                        "interval_seconds", Map.of(
                                                "type", "integer",
                                                "description", "执行间隔（秒），仅 create 时需要。例如：5分钟=300，1小时=3600"
                                        ),
                                        "task_description", Map.of(
                                                "type", "string",
                                                "description", "任务描述，仅 create 时需要。如'查微博热搜'、'查天气'"
                                        ),
                                        "task_id", Map.of(
                                                "type", "string",
                                                "description", "要取消的任务ID，仅 cancel 时指定。不指定则取消该用户所有任务"
                                        )
                                ),
                                "required", List.of("action")
                        )
                )
        );
    }

    public String execute(String functionName, String argumentsJson, String userId) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String action = arguments.path("action").asText("").trim();

            switch (action) {
                case "create":
                    return handleCreate(arguments, userId);
                case "cancel":
                    return handleCancel(arguments, userId);
                case "list":
                    return handleList(userId);
                default:
                    return "工具调用失败：不支持的操作 " + action;
            }
        } catch (Exception e) {
            log.error("周期任务工具执行失败: {}", e.getMessage());
            return "工具调用失败：参数解析错误";
        }
    }

    private String handleCreate(JsonNode arguments, String userId) {
        int intervalSeconds = arguments.path("interval_seconds").asInt(0);
        String description = arguments.path("task_description").asText("").trim();

        if (intervalSeconds <= 0) {
            return "工具调用失败：interval_seconds 必须大于 0";
        }
        if (description.isEmpty()) {
            return "工具调用失败：task_description 不能为空";
        }

        String taskId = schedulerService.createRepeatingTask(userId, intervalSeconds, description);
        String intervalDesc = formatInterval(intervalSeconds);

        log.info("已创建周期任务: userId={}, interval={}s, desc={}", userId, intervalSeconds, description);
        return "✅ 已创建周期任务！\n" +
                "🔄 任务：" + description + "\n" +
                "⏱️ 间隔：" + intervalDesc + "\n" +
                "📋 任务ID：" + taskId + "\n" +
                "💡 发送\"取消任务 " + taskId + "\"可随时取消";
    }

    private String handleCancel(JsonNode arguments, String userId) {
        String taskId = arguments.path("task_id").asText("").trim();

        if (!taskId.isEmpty()) {
            boolean success = schedulerService.cancelTask(taskId);
            return success ? "✅ 任务 " + taskId + " 已取消" : "❌ 未找到任务 " + taskId;
        }

        // 没有指定 taskId，取消该用户所有任务
        int count = schedulerService.cancelAllTasks(userId);
        if (count > 0) {
            return "✅ 已取消你的全部 " + count + " 个定时任务";
        }
        return "📋 你当前没有任何活跃的定时任务";
    }

    private String handleList(String userId) {
        List<ScheduledTaskEntity> tasks = schedulerService.listActiveTasks(userId);
        if (tasks.isEmpty()) {
            return "📋 你当前没有任何活跃的定时任务";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 你的定时任务列表：\n\n");
        for (int i = 0; i < tasks.size(); i++) {
            ScheduledTaskEntity task = tasks.get(i);
            sb.append(i + 1).append(". ");
            if ("remind".equals(task.getTaskType())) {
                sb.append("⏰ 提醒：").append(task.getDescription());
                if (task.getDelaySeconds() != null) {
                    sb.append("（").append(formatDelay(task.getDelaySeconds().intValue())).append("后）");
                }
            } else {
                sb.append("🔄 周期：").append(task.getDescription());
                if (task.getIntervalSeconds() != null) {
                    sb.append("（每").append(formatInterval(task.getIntervalSeconds().intValue())).append("）");
                }
            }
            sb.append("\n   ID：").append(task.getTaskId()).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatInterval(int seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分钟";
    }

    private String formatDelay(int seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分钟";
    }
}
