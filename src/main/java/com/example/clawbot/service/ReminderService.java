package com.example.clawbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性定时提醒业务服务，负责提醒的创建、内存存储和到期查询。
 * 为保持实现简单，应用重启后尚未发送的提醒会丢失。
 */
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
        ReminderTask task = new ReminderTask(id, userId, content, triggerAt, type);
        reminders.put(id, task);
        log.info("创建提醒: id={}, userId={}, triggerAt={}, type={}",
                id, userId, triggerAt, type);
        return task;
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
            ReminderType type
    ) {
    }
}
