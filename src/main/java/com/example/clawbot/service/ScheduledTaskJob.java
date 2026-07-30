package com.example.clawbot.service;

import com.example.clawbot.entity.ScheduledTask;
import com.example.clawbot.repository.ScheduledTaskRepository;
import com.github.wechat.ilink.sdk.ILinkClient;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Quartz 定时任务执行器。
 *
 * <p>替代原来每分钟轮询扫描的方式，由 Quartz 在精确时间触发执行。
 * 每个定时任务创建时注册一个 Quartz Job，到期后自动执行。</p>
 */
@Slf4j
@Component
public class ScheduledTaskJob implements Job {

    @Autowired
    private ScheduledTaskRepository repository;

    @Autowired
    private WeChatBotService weChatBotService;

    @Autowired
    private WeChatSessionManager sessionManager;

    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private WeatherService weatherService;

    /** 延迟注入调度器，避免循环依赖 */
    @Lazy
    @Autowired
    private ScheduledTaskScheduler taskScheduler;

    /**
     * Quartz 调用的执行入口。
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getMergedJobDataMap();
        long taskId = dataMap.getLong("taskId");

        log.info("Quartz 触发定时任务: taskId={}", taskId);

        Optional<ScheduledTask> opt = repository.findById(taskId);
        if (opt.isEmpty()) {
            log.warn("定时任务不存在: taskId={}", taskId);
            return;
        }

        ScheduledTask task = opt.get();

        // 跳过已取消的任务
        if ("CANCELLED".equals(task.getStatus())) {
            log.info("定时任务已取消，跳过执行: taskId={}", taskId);
            return;
        }

        try {
            // 执行任务
            executeTask(task);

            // 更新任务状态
            if ("NONE".equals(task.getRepeatType())) {
                // 一次性任务 → 标记完成
                task.setStatus("COMPLETED");
                repository.save(task);
                log.info("一次性任务已完成: taskId={}", taskId);
            } else {
                // 周期性任务 → 更新下次执行时间，并重新注册 Quartz 触发器
                task.setExecuteAt(calculateNextTime(task));
                repository.save(task);

                // 重新调度下次触发
                taskScheduler.scheduleTask(task);
                log.info("周期性任务已重新调度: taskId={}, nextTime={}", taskId, task.getExecuteAt());
            }

        } catch (Exception e) {
            log.error("定时任务执行失败: taskId={}, type={}, content={}", taskId, task.getTaskType(), task.getContent(), e);
        }
    }

    /**
     * 执行具体任务逻辑。
     */
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

    /**
     * 计算周期性任务的下次执行时间。
     */
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
