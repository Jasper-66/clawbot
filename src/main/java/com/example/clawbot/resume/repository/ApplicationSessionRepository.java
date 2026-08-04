package com.example.clawbot.resume.repository;

import com.example.clawbot.resume.model.JobListing;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ApplicationSessionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void createTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS job_search_sessions (
                    user_id    TEXT PRIMARY KEY,
                    keyword    TEXT NOT NULL,
                    city       TEXT NOT NULL,
                    jobs_json  TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
    }

    public void saveRecentSearch(
            String userId,
            String keyword,
            String city,
            List<JobListing> jobs,
            long createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO job_search_sessions (
                    user_id, keyword, city, jobs_json, created_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    keyword = excluded.keyword,
                    city = excluded.city,
                    jobs_json = excluded.jobs_json,
                    created_at = excluded.created_at
                """, userId, keyword, city, writeJson(jobs), createdAt);
    }

    public Optional<RecentSearch> findRecentSearch(String userId) {
        return jdbcTemplate.query("""
                SELECT keyword, city, jobs_json, created_at
                FROM job_search_sessions
                WHERE user_id = ?
                """, (resultSet, rowNumber) -> new RecentSearch(
                resultSet.getString("keyword"),
                resultSet.getString("city"),
                readJobListings(resultSet.getString("jobs_json")),
                resultSet.getLong("created_at")
        ), userId).stream().findFirst();
    }

    public void deleteRecentSearch(String userId) {
        jdbcTemplate.update(
                "DELETE FROM job_search_sessions WHERE user_id = ?",
                userId
        );
    }

    private List<JobListing> readJobListings(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, JobListing.class)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取最近岗位搜索记录", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存投递会话数据", exception);
        }
    }

    public record RecentSearch(
            String keyword,
            String city,
            List<JobListing> jobs,
            long createdAt
    ) {}

}
