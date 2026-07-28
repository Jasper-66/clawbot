package com.example.clawbot.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 会话数据访问层，管理 conversations 表的 CRUD 操作
@Repository               // 告诉 Spring：这是一个数据访问组件
@RequiredArgsConstructor       // 自动生成构造函数，注入 final 字段
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS conversations (
                id          TEXT PRIMARY KEY,
                user_id     TEXT NOT NULL,
                title       TEXT,
                created_at  TEXT NOT NULL,
                updated_at  TEXT NOT NULL
            )
            """;
        jdbcTemplate.execute(sql);

        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_conversations_user_id_unique
                ON conversations(user_id)
            """);

    }

    public Optional<Map<String, Object>> findByUserId(String userId) {
        String sql = "SELECT * FROM conversations WHERE user_id = ?";
        List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, userId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public String getOrCreate(String userId, String firstMessage) {
        Optional<Map<String, Object>> existing = findByUserId(userId);
        if (existing.isPresent()) {
            String id = (String) existing.get().get("id");
            touch(id);
            return id;
        }

        String id = UUID.randomUUID().toString();
        String now = OffsetDateTime.now(ZONE).toString();
        String title = firstMessage != null && firstMessage.length() > 30
                ? firstMessage.substring(0, 30) + "..."
                : firstMessage;

        jdbcTemplate.update(
                "INSERT INTO conversations (id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id, userId, title, now, now
        );
        return id;
    }

    public void touch(String conversationId) {
        String now = OffsetDateTime.now(ZONE).toString();
        jdbcTemplate.update(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                now, conversationId
        );
    }
}
