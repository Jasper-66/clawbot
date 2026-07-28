package com.example.clawbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 定时提醒服务，支持一次性提醒和周期性提醒的内存存储与到期查询
@Slf4j
@Service
public class ReminderService {

    private final ConcurrentHashMap<String, ReminderTask> reminders = new ConcurrentHashMap<>();

    public ReminderTask createReminder(String userId, String content,
                                       Instant triggerAt, ReminderType type) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无法识别当前微信用户");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }
        if (triggerAt == null || !triggerAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("提醒时间必须晚于当前时间");
        }
        if (type == null) {
            throw new IllegalArgumentException("提醒方式不能为空");
        }

        String id = UUID.randomUUID().toString();
        ReminderTask task = new ReminderTask(id, userId, content, triggerAt, type, false, 0);
        reminders.put(id, task);
        log.info("创建一次性提醒: id={}, userId={}, triggerAt={}, type={}",
                id, userId, triggerAt, type);
        return task;
    }

    public ReminderTask createPeriodicReminder(String userId, String content,
                                               Instant firstTriggerAt, ReminderType type,
                                               long intervalSeconds) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("无法识别当前微信用户");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }
        if (firstTriggerAt == null || !firstTriggerAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("首次提醒时间必须晚于当前时间");
        }
        if (type == null) {
            throw new IllegalArgumentException("提醒方式不能为空");
        }
        if (intervalSeconds < 60) {
            throw new IllegalArgumentException("周期性提醒的间隔不能少于 60 秒（1 分钟）");
        }

        String id = UUID.randomUUID().toString();
        ReminderTask task = new ReminderTask(id, userId, content, firstTriggerAt, type, true, intervalSeconds);
        reminders.put(id, task);
        log.info("创建周期性提醒: id={}, userId={}, firstTriggerAt={}, interval={}s, type={}",
                id, userId, firstTriggerAt, intervalSeconds, type);
        return task;
    }

    public void reschedule(String reminderId) {
        ReminderTask task = reminders.get(reminderId);
        if (task == null || !task.periodic()) {
            return;
        }
        Instant nextTrigger = Instant.now().plusSeconds(task.intervalSeconds());
        reminders.put(reminderId, task.withTriggerAt(nextTrigger));
        log.info("周期性提醒已重新调度: id={}, nextTriggerAt={}", reminderId, nextTrigger);
    }

    public List<ReminderTask> getDueReminders() {
        return getDueReminders(Instant.now());
    }

    public List<ReminderTask> getDueReminders(Instant now) {
        return reminders.values().stream()
                .filter(task -> !task.triggerAt().isAfter(now))
                .sorted(Comparator.comparing(ReminderTask::triggerAt))
                .toList();
    }

    public void markSent(String reminderId) {
        reminders.remove(reminderId);
    }

    public enum ReminderType {
        
        TEXT,
        
        VOICE,
        
        BOTH
    }

    public record ReminderTask(
            String id,
            String userId,
            String content,
            Instant triggerAt,
            ReminderType type,
            boolean periodic,
            long intervalSeconds
    ) {
        
        public ReminderTask withTriggerAt(Instant newTriggerAt) {
            return new ReminderTask(id, userId, content, newTriggerAt, type, periodic, intervalSeconds);
        }
    }
}
