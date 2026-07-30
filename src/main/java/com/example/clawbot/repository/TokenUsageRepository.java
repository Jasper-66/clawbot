package com.example.clawbot.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class TokenUsageRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public void createTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS token_usage (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT,
                prompt_tokens INTEGER NOT NULL DEFAULT 0,
                completion_tokens INTEGER NOT NULL DEFAULT 0,
                total_tokens INTEGER NOT NULL DEFAULT 0,
                model TEXT,
                created_at TEXT NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_token_usage_created_at
                ON token_usage(created_at)
            """);
    }

    public void record(String conversationId, int promptTokens, int completionTokens, int totalTokens, String model) {
        String now = java.time.OffsetDateTime.now(ZONE).toString();
        jdbcTemplate.update(
                "INSERT INTO token_usage (conversation_id, prompt_tokens, completion_tokens, total_tokens, model, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                conversationId, promptTokens, completionTokens, totalTokens, model, now
        );
    }

    public List<Map<String, Object>> getDailyUsage(int days) {
        LocalDate today = LocalDate.now(ZONE);
        List<Map<String, Object>> result = new java.util.ArrayList<>();

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String dateStr = date.toString();
            String nextDateStr = date.plusDays(1).toString();

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT COALESCE(SUM(prompt_tokens),0) AS prompt, COALESCE(SUM(completion_tokens),0) AS completion, COALESCE(SUM(total_tokens),0) AS total FROM token_usage WHERE created_at >= ? AND created_at < ?",
                    dateStr, nextDateStr
            );

            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("date", date.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd")));
            dayData.put("promptTokens", ((Number) row.get("prompt")).intValue());
            dayData.put("completionTokens", ((Number) row.get("completion")).intValue());
            dayData.put("totalTokens", ((Number) row.get("total")).intValue());
            result.add(dayData);
        }
        return result;
    }

    public int getTotalTokens() {
        Integer total = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(total_tokens),0) FROM token_usage", Integer.class);
        return total != null ? total : 0;
    }

    public int getTodayTokens() {
        String today = LocalDate.now(ZONE).toString();
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_tokens),0) FROM token_usage WHERE created_at >= ?",
                Integer.class, today);
        return total != null ? total : 0;
    }
}
