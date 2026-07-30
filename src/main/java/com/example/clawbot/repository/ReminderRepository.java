package com.example.clawbot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

// 提醒数据访问层，管理 reminders 表的 CRUD 操作
@Slf4j
@Repository
@RequiredArgsConstructor
public class ReminderRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public void createTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS reminders (
                id               TEXT PRIMARY KEY,
                user_id          TEXT NOT NULL,
                content          TEXT NOT NULL,
                trigger_at       TEXT NOT NULL,
                reminder_type    TEXT NOT NULL DEFAULT 'text',
                periodic         INTEGER NOT NULL DEFAULT 0,
                interval_seconds INTEGER NOT NULL DEFAULT 0,
                status           TEXT NOT NULL DEFAULT 'pending',
                created_at       TEXT NOT NULL,
                updated_at       TEXT NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_reminders_user_id
                ON reminders(user_id)
            """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_reminders_status_trigger
                ON reminders(status, trigger_at)
            """);
    }

    public void insert(String id, String userId, String content, Instant triggerAt,
                       String reminderType, boolean periodic, long intervalSeconds) {
        String now = OffsetDateTime.now(ZONE).toString();
        jdbcTemplate.update(
                "INSERT INTO reminders (id, user_id, content, trigger_at, reminder_type, periodic, interval_seconds, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
                id, userId, content, triggerAt.toString(),
                reminderType, periodic ? 1 : 0, intervalSeconds, now, now
        );
        log.info("提醒已写入数据库: id={}, userId={}, triggerAt={}, periodic={}", id, userId, triggerAt, periodic);
    }

    public void updateTriggerAt(String id, Instant nextTriggerAt) {
        String now = OffsetDateTime.now(ZONE).toString();
        jdbcTemplate.update(
                "UPDATE reminders SET trigger_at = ?, updated_at = ? WHERE id = ?",
                nextTriggerAt.toString(), now, id
        );
        log.info("周期性提醒下次触发时间已更新: id={}, nextTriggerAt={}", id, nextTriggerAt);
    }

    public void markSent(String id) {
        String now = OffsetDateTime.now(ZONE).toString();
        jdbcTemplate.update(
                "UPDATE reminders SET status = 'sent', updated_at = ? WHERE id = ?",
                now, id
        );
        log.info("提醒已标记为已发送: id={}", id);
    }

    public void markCancelled(String id) {
        String now = OffsetDateTime.now(ZONE).toString();
        jdbcTemplate.update(
                "UPDATE reminders SET status = 'cancelled', updated_at = ? WHERE id = ?",
                now, id
        );
        log.info("提醒已标记为已取消: id={}", id);
    }

    public List<ReminderRow> findPendingByTriggerAtBefore(Instant now) {
        String sql = """
            SELECT * FROM reminders
            WHERE status = 'pending' AND trigger_at <= ?
            ORDER BY trigger_at ASC
            """;
        return jdbcTemplate.queryForList(sql, now.toString()).stream()
                .map(this::mapRow)
                .toList();
    }

    public List<ReminderRow> findMissedDuring(Instant from, Instant to) {
        String sql = """
            SELECT * FROM reminders
            WHERE status = 'pending' AND trigger_at > ? AND trigger_at <= ?
            ORDER BY trigger_at ASC
            """;
        return jdbcTemplate.queryForList(sql, from.toString(), to.toString()).stream()
                .map(this::mapRow)
                .toList();
    }

    public List<ReminderRow> findAllPending() {
        String sql = """
            SELECT * FROM reminders
            WHERE status = 'pending'
            ORDER BY trigger_at ASC
            """;
        return jdbcTemplate.queryForList(sql).stream()
                .map(this::mapRow)
                .toList();
    }

    private ReminderRow mapRow(java.util.Map<String, Object> row) {
        return new ReminderRow(
                (String) row.get("id"),
                (String) row.get("user_id"),
                (String) row.get("content"),
                Instant.parse((String) row.get("trigger_at")),
                (String) row.get("reminder_type"),
                ((Number) row.get("periodic")).intValue() == 1,
                ((Number) row.get("interval_seconds")).longValue(),
                (String) row.get("status")
        );
    }

    public record ReminderRow(
            String id,
            String userId,
            String content,
            Instant triggerAt,
            String reminderType,
            boolean periodic,
            long intervalSeconds,
            String status
    ) {}
}
