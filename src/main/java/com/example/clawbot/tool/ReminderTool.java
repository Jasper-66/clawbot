package com.example.clawbot.tool;

import com.example.clawbot.service.SchedulerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 定时提醒工具 — Function Calling 工具。
 *
 * <p>当用户说"提醒我X分钟后做某事"时，LLM 会调用此工具创建提醒。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTool {

    private final SchedulerService schedulerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "set_reminder";
    private static final String DESCRIPTION = "设置一个定时提醒。当用户说'提醒我X分钟后/秒后做某事'、'X分钟后提醒我'、'设个闹钟'等类似请求时使用此工具。";

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
                                        "delay_seconds", Map.of(
                                                "type", "integer",
                                                "description", "多少秒后提醒。例如：5分钟后=300，30秒后=30，1小时后=3600"
                                        ),
                                        "message", Map.of(
                                                "type", "string",
                                                "description", "提醒内容，如'喝水'、'开会'、'吃药'"
                                        )
                                ),
                                "required", List.of("delay_seconds", "message")
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
            int delaySeconds = arguments.path("delay_seconds").asInt(0);
            String message = arguments.path("message").asText("").trim();

            if (delaySeconds <= 0) {
                return "工具调用失败：delay_seconds 必须大于 0";
            }
            if (message.isEmpty()) {
                return "工具调用失败：message 不能为空";
            }

            String taskId = schedulerService.createReminder(userId, delaySeconds, message);
            String timeDesc = formatDelay(delaySeconds);

            log.info("已创建提醒: userId={}, delay={}s, message={}", userId, delaySeconds, message);
            return "✅ 已设置提醒！\n" +
                    "⏰ " + timeDesc + "后提醒你：" + message + "\n" +
                    "📋 任务ID：" + taskId;

        } catch (Exception e) {
            log.error("提醒工具执行失败: {}", e.getMessage());
            return "工具调用失败：参数解析错误";
        }
    }

    private String formatDelay(int seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分钟";
    }
}
