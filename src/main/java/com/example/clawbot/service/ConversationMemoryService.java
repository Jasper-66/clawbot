package com.example.clawbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆服务 — 基于 Redis 实现对话历史的持久化存储与智能压缩。
 *
 * 两层记忆架构：
 * - 短期记忆：最近 N 条消息（Redis List，JSON 字符串），保持对话连贯性
 * - 长期记忆：历史对话的摘要文本（Redis String），跨越重启持久保留
 */
@Slf4j
@Service
public class ConversationMemoryService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${clawbot.memory.short-term-size:20}")
    private int shortTermSize;

    @Value("${clawbot.memory.long-term-threshold:30}")
    private int longTermThreshold;

    @Value("${clawbot.memory.ttl-days:7}")
    private int ttlDays;

    private static final String CONV_PREFIX = "clawbot:conv:";
    private static final String MEMORY_PREFIX = "clawbot:memory:";
    private static final String VOICE_PREFIX = "clawbot:voice:";
    private static final String MSGIDS_KEY = "clawbot:msgids";

    public ConversationMemoryService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ─── 对话上下文 ────────────────────────────────────────

    /** 获取用户的完整对话上下文：长期记忆 + 近期消息 */
    public List<Map<String, Object>> getContext(String userId) {
        List<Map<String, Object>> context = new ArrayList<>();

        // 长期记忆作为系统级上下文注入
        String longTermMemory = getLongTermMemory(userId);
        if (longTermMemory != null && !longTermMemory.isBlank()) {
            context.add(Map.of("role", "system", "content",
                    "[历史对话摘要] 以下是用户与你之前对话的要点摘要，可参考但不要生硬复述：\n" + longTermMemory));
        }

        // 短期对话历史
        context.addAll(getRecentMessages(userId));

        return context;
    }

    /** 追加一条消息到用户对话历史 */
    public void appendMessage(String userId, String role, String content) {
        String key = CONV_PREFIX + userId;
        try {
            String json = objectMapper.writeValueAsString(Map.of("role", role, "content", content));
            redis.opsForList().rightPush(key, json);
        } catch (Exception e) {
            log.error("序列化消息失败: userId={}, role={}", userId, role, e);
            return;
        }
        refreshTtl(key);

        // 保持 List 长度不超过 shortTermSize * 2，为压缩留缓冲
        Long size = redis.opsForList().size(key);
        if (size != null && size > shortTermSize * 2L) {
            redis.opsForList().trim(key, -(shortTermSize), -1);
        }
    }

    /** 获取最近 N 条消息（短期记忆） */
    public List<Map<String, Object>> getRecentMessages(String userId) {
        String key = CONV_PREFIX + userId;
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) {
            return Collections.emptyList();
        }

        long start = Math.max(0, size - shortTermSize);
        List<String> raw = redis.opsForList().range(key, start, -1);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> messages = new ArrayList<>();
        for (String json : raw) {
            try {
                Map<String, Object> msg = objectMapper.readValue(json,
                        new TypeReference<LinkedHashMap<String, Object>>() {});
                messages.add(msg);
            } catch (Exception e) {
                log.warn("反序列化消息失败: userId={}, json={}", userId, json, e);
            }
        }
        return messages;
    }

    // ─── 长期记忆（摘要压缩）────────────────────────────────

    /** 获取长期记忆摘要 */
    public String getLongTermMemory(String userId) {
        return redis.opsForValue().get(MEMORY_PREFIX + userId);
    }

    /** 追加长期记忆摘要（与已有摘要合并） */
    public void appendLongTermMemory(String userId, String summary) {
        if (summary == null || summary.isBlank()) return;
        String key = MEMORY_PREFIX + userId;
        String existing = getLongTermMemory(userId);
        String updated = (existing != null && !existing.isBlank())
                ? existing + "\n" + summary
                : summary;
        redis.opsForValue().set(key, updated, Duration.ofDays(ttlDays));
        log.info("长期记忆已更新: userId={}, summaryLength={}", userId, summary.length());
    }

    /** 检查是否需要压缩短期记忆到长期记忆 */
    public boolean needsCompression(String userId) {
        String key = CONV_PREFIX + userId;
        Long size = redis.opsForList().size(key);
        return size != null && size >= longTermThreshold;
    }

    /** 从列表头部取出最旧的 N 条消息用于压缩，返回被取出的消息 */
    public List<Map<String, Object>> extractForCompression(String userId, int count) {
        String key = CONV_PREFIX + userId;
        List<Map<String, Object>> extracted = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String json = redis.opsForList().leftPop(key);
            if (json == null) break;
            try {
                Map<String, Object> msg = objectMapper.readValue(json,
                        new TypeReference<LinkedHashMap<String, Object>>() {});
                extracted.add(msg);
            } catch (Exception e) {
                log.warn("反序列化待压缩消息失败: userId={}", userId, e);
            }
        }
        refreshTtl(key);
        return extracted;
    }

    // ─── 用户音色偏好 ──────────────────────────────────────

    public void setVoicePreference(String userId, String voice) {
        String key = VOICE_PREFIX + userId;
        redis.opsForValue().set(key, voice, Duration.ofDays(ttlDays));
    }

    public String getVoicePreference(String userId) {
        return redis.opsForValue().get(VOICE_PREFIX + userId);
    }

    // ─── 消息去重 ──────────────────────────────────────────

    /** 标记消息已处理，返回 true 表示首次处理 */
    public boolean markProcessed(Long msgId) {
        Long added = redis.opsForSet().add(MSGIDS_KEY, msgId.toString());
        if (Boolean.TRUE.equals(redis.hasKey(MSGIDS_KEY))) {
            redis.expire(MSGIDS_KEY, 2, TimeUnit.HOURS);
        }
        return added != null && added > 0;
    }

    // ─── 辅助方法 ──────────────────────────────────────────

    private void refreshTtl(String key) {
        redis.expire(key, Duration.ofDays(ttlDays));
    }

    /** 清除用户全部记忆（对话历史 + 长期记忆 + 音色偏好） */
    public void clearUserMemory(String userId) {
        redis.delete(CONV_PREFIX + userId);
        redis.delete(MEMORY_PREFIX + userId);
        redis.delete(VOICE_PREFIX + userId);
        log.info("用户记忆已清除: userId={}", userId);
    }
}
