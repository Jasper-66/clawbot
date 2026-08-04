package com.example.clawbot.resume.repository;

import com.example.clawbot.resume.model.ApplicationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 保存投递尝试和最终结果。 */
@Repository
@RequiredArgsConstructor
public class ApplicationRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public void createTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS application_records (
                record_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                job_id TEXT,
                application_id TEXT,
                platform TEXT,
                job_title TEXT NOT NULL,
                company TEXT,
                salary TEXT,
                city TEXT,
                status TEXT NOT NULL,
                applied_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                remark TEXT
            )
            """);
        addColumnIfMissing("job_id", "TEXT");
        addColumnIfMissing("application_id", "TEXT");
        addColumnIfMissing("platform", "TEXT");
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_application_records_user_time
                ON application_records(user_id, applied_at DESC)
            """);
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_application_records_user_job
                ON application_records(user_id, job_id)
            """);
    }

    public boolean insertIfAbsent(ApplicationRecord record) {
        return jdbcTemplate.update("""
                INSERT OR IGNORE INTO application_records (
                    record_id, user_id, job_id, application_id, platform,
                    job_title, company, salary, city, status,
                    applied_at, updated_at, remark
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.getRecordId(), record.getUserId(), record.getJobId(),
                record.getApplicationId(), record.getPlatform(), record.getJobTitle(),
                record.getCompany(), record.getSalary(), record.getCity(), record.getStatus(),
                record.getAppliedAt(), record.getUpdatedAt(), record.getRemark()) == 1;
    }

    public Optional<ApplicationRecord> findByUserIdAndJobId(String userId, String jobId) {
        return jdbcTemplate.query("""
                SELECT * FROM application_records
                WHERE user_id = ? AND job_id = ?
                ORDER BY applied_at DESC LIMIT 1
                """, this::mapRow, userId, jobId).stream().findFirst();
    }

    public List<ApplicationRecord> findByUserId(String userId) {
        return jdbcTemplate.query("""
                SELECT * FROM application_records
                WHERE user_id = ? ORDER BY applied_at DESC
                """, this::mapRow, userId);
    }

    public boolean restartFailed(String recordId, String updatedAt) {
        return jdbcTemplate.update("""
                UPDATE application_records
                SET status = 'PROCESSING', updated_at = ?, remark = '正在投递'
                WHERE record_id = ? AND status = 'FAILED'
                """, updatedAt, recordId) == 1;
    }

    public void complete(
            String recordId,
            String applicationId,
            String status,
            String appliedAt,
            String updatedAt,
            String remark) {
        int rows = jdbcTemplate.update("""
                UPDATE application_records
                SET application_id = ?, status = ?, applied_at = ?, updated_at = ?, remark = ?
                WHERE record_id = ?
                """, applicationId, status, appliedAt, updatedAt, remark, recordId);
        if (rows != 1) throw new IllegalStateException("投递记录不存在: " + recordId);
    }

    private ApplicationRecord mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return ApplicationRecord.builder()
                .recordId(rs.getString("record_id"))
                .userId(rs.getString("user_id"))
                .jobId(rs.getString("job_id"))
                .applicationId(rs.getString("application_id"))
                .platform(rs.getString("platform"))
                .jobTitle(rs.getString("job_title"))
                .company(rs.getString("company"))
                .salary(rs.getString("salary"))
                .city(rs.getString("city"))
                .status(rs.getString("status"))
                .appliedAt(rs.getString("applied_at"))
                .updatedAt(rs.getString("updated_at"))
                .remark(rs.getString("remark"))
                .build();
    }

    private void addColumnIfMissing(String name, String type) {
        List<String> columns = jdbcTemplate.query(
                "PRAGMA table_info(application_records)",
                (rs, rowNumber) -> rs.getString("name"));
        if (!columns.contains(name)) {
            jdbcTemplate.execute("ALTER TABLE application_records ADD COLUMN " + name + " " + type);
        }
    }
}
