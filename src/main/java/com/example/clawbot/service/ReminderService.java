package com.example.clawbot.service;

import com.example.clawbot.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 定时提醒服务，支持一次性提醒和周期性提醒，数据持久化到 SQLite
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepository;

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
        reminderRepository.insert(id, userId, content, triggerAt, type.name(), false, 0);
        log.info("创建一次性提醒: id={}, userId={}, triggerAt={}, type={}", id, userId, triggerAt, type);
        return new ReminderTask(id, userId, content, triggerAt, type, false, 0);
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
        reminderRepository.insert(id, userId, content, firstTriggerAt, type.name(), true, intervalSeconds);
        log.info("创建周期性提醒: id={}, userId={}, firstTriggerAt={}, interval={}s, type={}",
                id, userId, firstTriggerAt, intervalSeconds, type);
        return new ReminderTask(id, userId, content, firstTriggerAt, type, true, intervalSeconds);
    }

    public void reschedule(String reminderId) {
        // 从数据库查出该提醒，计算下次触发时间
        List<ReminderRepository.ReminderRow> allPending = reminderRepository.findAllPending();
        ReminderRepository.ReminderRow target = allPending.stream()
                .filter(r -> r.id().equals(reminderId))
                .findFirst()
                .orElse(null);
        if (target == null || !target.periodic()) {
            return;
        }
        Instant nextTrigger = Instant.now().plusSeconds(target.intervalSeconds());
        reminderRepository.updateTriggerAt(reminderId, nextTrigger);
        log.info("周期性提醒已重新调度: id={}, nextTriggerAt={}", reminderId, nextTrigger);
    }

    public List<ReminderTask> getDueReminders() {
        return getDueReminders(Instant.now());
    }

    public List<ReminderTask> getDueReminders(Instant now) {
        return reminderRepository.findPendingByTriggerAtBefore(now).stream()
                .map(row -> new ReminderTask(
                        row.id(), row.userId(), row.content(), row.triggerAt(),
                        ReminderType.valueOf(row.reminderType()),
                        row.periodic(), row.intervalSeconds()
                ))
                .toList();
    }

    public void markSent(String reminderId) {
        reminderRepository.markSent(reminderId);
    }

    // ═══════════════════════════════════════════════════
    // 重启恢复相关
    // ═══════════════════════════════════════════════════

    /**
     * 获取所有待发送的提醒（重启恢复用）。
     */
    public List<ReminderTask> getAllPendingReminders() {
        return reminderRepository.findAllPending().stream()
                .map(row -> new ReminderTask(
                        row.id(), row.userId(), row.content(), row.triggerAt(),
                        ReminderType.valueOf(row.reminderType()),
                        row.periodic(), row.intervalSeconds()
                ))
                .toList();
    }

    /**
     * 获取在停机期间遗漏的提醒（triggerAt 在 [from, to] 之间且仍为 pending）。
     */
    public List<ReminderTask> getMissedReminders(Instant downtimeStart, Instant downtimeEnd) {
        return reminderRepository.findMissedDuring(downtimeStart, downtimeEnd).stream()
                .map(row -> new ReminderTask(
                        row.id(), row.userId(), row.content(), row.triggerAt(),
                        ReminderType.valueOf(row.reminderType()),
                        row.periodic(), row.intervalSeconds()
                ))
                .toList();
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
    ) {}
}
