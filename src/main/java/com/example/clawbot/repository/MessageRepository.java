package com.example.clawbot.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 消息数据访问层，管理 messages 表的读写和历史查询
@Repository               //   告诉Spring 这是一个数据库访问组件
@RequiredArgsConstructor   //自定生成构造函数，注入Final字段
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_HISTORY = 10;

    public void createTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS messages (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                role            TEXT NOT NULL,
                content         TEXT NOT NULL,
                message_type    TEXT NOT NULL DEFAULT 'text',
                metadata        TEXT,
                created_at      TEXT NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id)
            )
            """);
        // 索引：按会话ID查询（最常用）
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_messages_conversation_id
                ON messages(conversation_id)
            """);
        // 索引：按时间排序（取最近N条时用）
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_messages_created_at
                ON messages(created_at)
            """);
    }

    public List<Map<String, Object>> findRecentByConversationId(String conversationId) {
        //sql:按时间倒序，取最近十条
        String sql = """
            SELECT * FROM messages
            WHERE conversation_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;
         //查找出来是倒序（最新的在前）
        List<Map<String, Object>> recent = jdbcTemplate.queryForList(sql, conversationId, MAX_HISTORY);
         //反转成正序（最旧的在前）
        List<Map<String, Object>> result = new ArrayList<>(recent);
        java.util.Collections.reverse(result);
        return result;
    }
    //存一条新消息
    public void insert(String conversationId, String role, String content,
                       String messageType, String metadata) {
        String now = OffsetDateTime.now(ZONE).toString();
        jdbcTemplate.update(
                "INSERT INTO messages (conversation_id, role, content, message_type, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                conversationId, role, content,
                messageType != null ? messageType : "text",
                metadata, now
        );
    }
}
