package com.example.clawbot.controller;

import com.example.clawbot.exception.Result;
import com.example.clawbot.repository.TokenUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final JdbcTemplate jdbcTemplate;
    private final TokenUsageRepository tokenUsageRepository;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        Map<String, Object> stats = new LinkedHashMap<>();

        Integer docCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_documents", Integer.class);
        stats.put("documentCount", docCount != null ? docCount : 0);

        String today = LocalDate.now(ZONE).toString();
        Integer todayConversations = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT conversation_id) FROM messages WHERE created_at >= ?",
                Integer.class, today);
        stats.put("todayConversations", todayConversations != null ? todayConversations : 0);

        Integer activeReminders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reminders WHERE status = 'pending'", Integer.class);
        stats.put("activeReminders", activeReminders != null ? activeReminders : 0);

        Integer totalMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages", Integer.class);
        stats.put("totalMessages", totalMessages != null ? totalMessages : 0);

        Integer totalConversations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations", Integer.class);
        stats.put("totalConversations", totalConversations != null ? totalConversations : 0);

        // Token 统计
        stats.put("totalTokens", tokenUsageRepository.getTotalTokens());
        stats.put("todayTokens", tokenUsageRepository.getTodayTokens());

        return Result.success(stats);
    }

    @GetMapping("/token-usage")
    public Result<List<Map<String, Object>>> tokenUsage() {
        return Result.success(tokenUsageRepository.getDailyUsage(7));
    }
}
