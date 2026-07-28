package com.example.clawbot.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
// 基于 SQLite 的聊天记忆实现，为 Spring AI 提供对话历史持久化
@Component
@RequiredArgsConstructor
public class SqliteChatMemory implements ChatMemory {

    private final JdbcTemplate jdbcTemplate;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public void add(String conversationId, List<Message> messages) {
        String now = OffsetDateTime.now(ZONE).toString();
        for (Message message : messages) {
            String metadataJson = null;
            try {
                if (message.getMetadata() != null && !message.getMetadata().isEmpty()) {
                    metadataJson = objectMapper.writeValueAsString(message.getMetadata());
                }
            } catch (Exception e) {
                log.warn("序列化消息元数据失败: {}", e.getMessage());
            }
            jdbcTemplate.update(
                    "INSERT INTO messages (conversation_id, role, content, message_type, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    conversationId,
                    message.getMessageType().name().toLowerCase(),
                    message.getText() != null ? message.getText() : "",
                    "text",
                    metadataJson,
                    now
            );
        }
        conversationRepository.touch(conversationId);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        String sql = """
            SELECT role, content, metadata FROM messages
            WHERE conversation_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, conversationId, lastN);
        List<Message> messages = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            try {
                messages.add(deserialize(rows.get(i)));
            } catch (Exception e) {
                log.warn("反序列化消息失败: {}", e.getMessage());
            }
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
    }

    private Message deserialize(Map<String, Object> row) {
        String role = (String) row.get("role");
        String content = (String) row.get("content");
        String metadataJson = (String) row.get("metadata");

        Map<String, Object> metadata = Map.of();
        if (metadataJson != null && !metadataJson.isEmpty()) {
            try {
                metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("解析消息元数据失败: {}", e.getMessage());
            }
        }

        return switch (role.toUpperCase()) {
            case "USER" -> new UserMessage(content, List.of(), metadata);
            case "ASSISTANT" -> new AssistantMessage(content, metadata);
            case "SYSTEM" -> new SystemMessage(content);
            case "TOOL_RESPONSE" -> new ToolResponseMessage(List.of(), metadata);
            default -> new UserMessage(content, List.of(), metadata);
        };
    }
}
