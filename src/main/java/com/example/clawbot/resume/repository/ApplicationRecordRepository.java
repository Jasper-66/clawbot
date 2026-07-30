package com.example.clawbot.resume.repository;

import com.example.clawbot.resume.model.ApplicationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ApplicationRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public void createTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS application_records (
                record_id   TEXT PRIMARY KEY,
                user_id     TEXT NOT NULL,
                job_title   TEXT NOT NULL,
                company     TEXT,
                salary      TEXT,
                city        TEXT,
                status      TEXT NOT NULL,
                match_score INTEGER,
                applied_at  TEXT NOT NULL,
                updated_at  TEXT NOT NULL,
                remark      TEXT
            )
            """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_application_records_user_time
                ON application_records(user_id, applied_at DESC)
            """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_application_records_user_status
                ON application_records(user_id, status)
            """);
    }

    public void insert(ApplicationRecord record) {
        jdbcTemplate.update("""
                INSERT INTO application_records (
                    record_id, user_id, job_title, company, salary, city,
                    status, match_score, applied_at, updated_at, remark
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.getRecordId(),
                record.getUserId(),
                record.getJobTitle(),
                record.getCompany(),
                record.getSalary(),
                record.getCity(),
                record.getStatus(),
                record.getMatchScore(),
                record.getAppliedAt(),
                record.getUpdatedAt(),
                record.getRemark()
        );
    }

    public List<ApplicationRecord> findByUserId(String userId) {
        return jdbcTemplate.query("""
                SELECT * FROM application_records
                WHERE user_id = ?
                ORDER BY applied_at DESC
                """, this::mapRow, userId);
    }

    public Optional<ApplicationRecord> findById(String recordId) {
        List<ApplicationRecord> records = jdbcTemplate.query("""
                SELECT * FROM application_records
                WHERE record_id = ?
                """, this::mapRow, recordId);
        return records.stream().findFirst();
    }

    public int updateStatus(String recordId, String status, String updatedAt) {
        return jdbcTemplate.update("""
                UPDATE application_records
                SET status = ?, updated_at = ?
                WHERE record_id = ?
                """, status, updatedAt, recordId);
    }

    private ApplicationRecord mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        Number matchScore = (Number) resultSet.getObject("match_score");
        return ApplicationRecord.builder()
                .recordId(resultSet.getString("record_id"))
                .userId(resultSet.getString("user_id"))
                .jobTitle(resultSet.getString("job_title"))
                .company(resultSet.getString("company"))
                .salary(resultSet.getString("salary"))
                .city(resultSet.getString("city"))
                .status(resultSet.getString("status"))
                .matchScore(matchScore == null ? null : matchScore.intValue())
                .appliedAt(resultSet.getString("applied_at"))
                .updatedAt(resultSet.getString("updated_at"))
                .remark(resultSet.getString("remark"))
                .build();
    }
}
