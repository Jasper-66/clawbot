package com.example.clawbot.memory;

import com.example.clawbot.entity.MessageLog;
import com.example.clawbot.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 SQLite 的聊天记忆持久化实现。
 *
 * <p>实现 Spring AI 的 {@link ChatMemory} 接口，将对话消息存储到
 * {@code message_log} 表中。应用重启后，历史对话自动从数据库恢复。</p>
 *
 * <p>conversationId 对应微信的 userId（from_user_id）。</p>
 */
@Component
@RequiredArgsConstructor
public class JpaChatMemory implements ChatMemory {

    private final MessageLogRepository repository;

    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message msg : messages) {
            String direction;
            String content = msg.getText();
            String msgType = "text";

            if (msg instanceof UserMessage) {
                direction = "in";
            } else if (msg instanceof AssistantMessage) {
                direction = "out";
            } else {
                // system / tool response 等不入库
                continue;
            }

            // 跳过占位消息（如图片标记）
            if (content == null || content.isBlank()) continue;

            repository.save(MessageLog.builder()
                    .userId(conversationId)
                    .content(content)
                    .msgType(msgType)
                    .direction(direction)
                    .build());
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        // 子查询取最近 lastN 条，外层按时间正序返回（对话自然顺序）
        List<MessageLog> logs = repository.findRecentByUserIdAsc(conversationId, lastN);

        List<Message> messages = new ArrayList<>();
        for (MessageLog log : logs) {
            if ("in".equals(log.getDirection())) {
                messages.add(new UserMessage(log.getContent()));
            } else if ("out".equals(log.getDirection())) {
                messages.add(new AssistantMessage(log.getContent()));
            }
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        // 按用户清空历史（可选功能，暂不暴露给外部）
        List<MessageLog> logs = repository.findByUserIdOrderByCreateTimeDesc(conversationId);
        repository.deleteAll(logs);
    }
}
