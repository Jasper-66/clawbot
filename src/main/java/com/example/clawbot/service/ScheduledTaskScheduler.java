package com.example.clawbot.service;

import com.example.clawbot.entity.ScheduledTask;
import com.example.clawbot.repository.ScheduledTaskRepository;
import com.github.wechat.ilink.sdk.ILinkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一定时任务调度器。
 *
 * <p>每分钟扫描一次到期任务，根据任务类型执行不同操作：</p>
 * <ul>
 *   <li>REMIND — 发送提醒消息</li>
 *   <li>IMAGE — 调用图片生成服务并发送图片</li>
 *   <li>WEATHER — 调用天气服务并发送结果</li>
 *   <li>CUSTOM — 发送自定义消息</li>
 * </ul>
 *
 * <p>一次性任务执行后标记为 COMPLETED，周期性任务更新下次执行时间。</p>
 *
 * <p>通过 {@link WeChatSessionManager} 获取活跃会话的 ILinkClient 发送消息。
 * 如果没有活跃会话，任务将跳过执行并记录警告。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskScheduler {

    private final ScheduledTaskRepository repository;
    private final WeChatBotService weChatBotService;
    private final WeChatSessionManager sessionManager;
    private final ImageGenerationService imageGenerationService;
    private final WeatherService weatherService;

    @Scheduled(fixedRate = 60000)
    public void scanAndExecute() {
        List<ScheduledTask> dueTasks = repository.findByStatusAndExecuteAtBefore("ACTIVE", LocalDateTime.now());
        if (dueTasks.isEmpty()) return;

        log.info("扫描到 {} 条到期定时任务", dueTasks.size());

        for (ScheduledTask task : dueTasks) {
            try {
                executeTask(task);

                if ("NONE".equals(task.getRepeatType())) {
                    task.setStatus("COMPLETED");
                } else {
                    task.setExecuteAt(calculateNextTime(task));
                }
                repository.save(task);
            } catch (Exception e) {
                log.error("定时任务执行失败: id={}, type={}, content={}", task.getId(), task.getTaskType(), task.getContent(), e);
            }
        }
    }

    private void executeTask(ScheduledTask task) {
        ILinkClient client = sessionManager.getActiveClient();
        if (client == null) {
            log.warn("无活跃会话，跳过定时任务: id={}, userId={}", task.getId(), task.getUserId());
            return;
        }

        switch (task.getTaskType()) {
            case "REMIND" -> {
                String msg = "🔔 提醒\n\n" + task.getContent();
                weChatBotService.sendReminder(client, task.getUserId(), msg);
            }
            case "IMAGE" -> {
                log.info("执行定时图片生成: content={}", task.getContent());
                try {
                    byte[] imageBytes = imageGenerationService.generateImage(task.getContent());
                    if (imageBytes != null && imageBytes.length > 0) {
                        weChatBotService.sendReminderImage(client, task.getUserId(), imageBytes, task.getContent());
                    } else {
                        weChatBotService.sendReminder(client, task.getUserId(), "⚠️ 定时图片生成失败：返回为空");
                    }
                } catch (Exception e) {
                    weChatBotService.sendReminder(client, task.getUserId(), "⚠️ 定时图片生成失败：" + e.getMessage());
                }
            }
            case "WEATHER" -> {
                log.info("执行定时天气查询: city={}", task.getContent());
                try {
                    String weather = weatherService.getWeather(task.getContent());
                    weChatBotService.sendReminder(client, task.getUserId(), "🌤️ 定时天气查询\n\n" + weather);
                } catch (Exception e) {
                    weChatBotService.sendReminder(client, task.getUserId(), "⚠️ 定时天气查询失败：" + e.getMessage());
                }
            }
            case "CUSTOM" -> {
                weChatBotService.sendReminder(client, task.getUserId(), task.getContent());
            }
            default -> {
                log.warn("未知任务类型: {}", task.getTaskType());
            }
        }
    }

    private LocalDateTime calculateNextTime(ScheduledTask task) {
        LocalDateTime current = task.getExecuteAt();
        return switch (task.getRepeatType()) {
            case "DAILY" -> current.plusDays(1);
            case "WEEKLY" -> current.plusWeeks(1);
            case "MONTHLY" -> current.plusMonths(1);
            default -> current.plusDays(1);
        };
    }
}
