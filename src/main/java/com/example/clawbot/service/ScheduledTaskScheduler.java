package com.example.clawbot.service;

import com.example.clawbot.entity.ScheduledTask;
import com.example.clawbot.repository.ScheduledTaskRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * Quartz 定时任务调度器。
 *
 * <p>替代原来 @Scheduled 每分钟轮询扫描的方式。
 * 每个任务创建时精确注册 Quartz 触发器，到期后由 Quartz 自动触发执行。</p>
 *
 * <h3>优势</h3>
 * <ul>
 *   <li>精确到秒级触发，不再有最多 1 分钟延迟</li>
 *   <li>无需每分钟扫描数据库，减少无效查询</li>
 *   <li>支持动态增删任务</li>
 * </ul>
 *
 * @see ScheduledTaskJob Quartz Job 执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskScheduler {

    private final SchedulerFactoryBean schedulerFactoryBean;
    private final ScheduledTaskRepository repository;

    /**
     * 应用启动时，加载所有 ACTIVE 任务并注册 Quartz 触发器。
     */
    @PostConstruct
    public void init() {
        List<ScheduledTask> activeTasks = repository.findByStatusAndExecuteAtBefore(
                "ACTIVE", LocalDateTime.now().plusYears(1));

        log.info("Quartz 初始化：加载 {} 条活跃定时任务", activeTasks.size());

        for (ScheduledTask task : activeTasks) {
            scheduleTask(task);
        }
    }

    /**
     * 将定时任务注册到 Quartz 调度器。
     *
     * <p>每个任务使用 taskId 作为 JobKey，精确设置触发时间。
     * 如果任务已存在，先移除再重新注册（更新触发时间）。</p>
     *
     * @param task 定时任务实体
     */
    public void scheduleTask(ScheduledTask task) {
        try {
            Scheduler scheduler = schedulerFactoryBean.getScheduler();

            JobKey jobKey = buildJobKey(task.getId());
            TriggerKey triggerKey = buildTriggerKey(task.getId());

            // 构建 JobDetail，通过 JobDataMap 传递 taskId
            JobDetail jobDetail = JobBuilder.newJob(ScheduledTaskJob.class)
                    .withIdentity(jobKey)
                    .usingJobData("taskId", task.getId())
                    .storeDurably(true) // 即使没有活跃触发器也保留 Job
                    .build();

            // 计算触发时间
            Date triggerTime = Date.from(
                    task.getExecuteAt().atZone(ZoneId.systemDefault()).toInstant());

            // 如果触发时间已过，设置为 1 秒后触发（立即执行）
            if (triggerTime.before(new Date())) {
                triggerTime = new Date(System.currentTimeMillis() + 1000);
                log.warn("任务触发时间已过，设置为立即执行: taskId={}, executeAt={}",
                        task.getId(), task.getExecuteAt());
            }

            // 构建 SimpleTrigger（一次性触发）
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .startAt(triggerTime)
                    .build();

            // 注册或更新调度
            if (scheduler.checkExists(jobKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
                log.info("Quartz 任务已更新: taskId={}, triggerTime={}", task.getId(), triggerTime);
            } else {
                scheduler.scheduleJob(jobDetail, trigger);
                log.info("Quartz 任务已注册: taskId={}, triggerTime={}", task.getId(), triggerTime);
            }

        } catch (SchedulerException e) {
            log.error("Quartz 调度失败: taskId={}", task.getId(), e);
        }
    }

    /**
     * 取消 Quartz 中的定时任务。
     *
     * @param taskId 任务 ID
     */
    public void cancelTask(Long taskId) {
        try {
            Scheduler scheduler = schedulerFactoryBean.getScheduler();
            JobKey jobKey = buildJobKey(taskId);

            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("Quartz 任务已取消: taskId={}", taskId);
            }
        } catch (SchedulerException e) {
            log.error("Quartz 取消任务失败: taskId={}", taskId, e);
        }
    }

    /**
     * 构建 JobKey。
     */
    private JobKey buildJobKey(Long taskId) {
        return new JobKey("task-" + taskId, "clawbot-tasks");
    }

    /**
     * 构建 TriggerKey。
     */
    private TriggerKey buildTriggerKey(Long taskId) {
        return new TriggerKey("trigger-" + taskId, "clawbot-tasks");
    }
}
