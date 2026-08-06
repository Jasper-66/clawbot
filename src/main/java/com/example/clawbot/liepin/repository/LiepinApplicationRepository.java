package com.example.clawbot.liepin.repository;

import com.example.clawbot.liepin.model.LiepinApplication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LiepinApplicationRepository {

    private final JdbcTemplate jdbcTemplate;

    public void createTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS liepin_applications (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                job_kind TEXT NOT NULL,
                job_title TEXT,
                company TEXT,
                city TEXT,
                salary TEXT,
                status TEXT NOT NULL,
                applied_at TIMESTAMP NOT NULL,
                error_message TEXT
            )
            """;
        jdbcTemplate.execute(sql);
        log.info("[数据库] liepin_applications 表初始化完成");
    }

    public void save(LiepinApplication application) {
        if (application.getId() == null) {
            application.setId(UUID.randomUUID().toString());
        }
        if (application.getAppliedAt() == null) {
            application.setAppliedAt(LocalDateTime.now());
        }
        
        String sql = """
            INSERT INTO liepin_applications (id, job_id, job_kind, job_title, company, city, salary, status, applied_at, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
            application.getId(),
            application.getJobId(),
            application.getJobKind(),
            application.getJobTitle(),
            application.getCompany(),
            application.getCity(),
            application.getSalary(),
            application.getStatus(),
            Timestamp.valueOf(application.getAppliedAt()),
            application.getErrorMessage()
        );
        log.info("[数据库] 保存投递记录: jobId={}, status={}", application.getJobId(), application.getStatus());
    }

    public List<LiepinApplication> findAll() {
        String sql = "SELECT * FROM liepin_applications ORDER BY applied_at DESC";
        return jdbcTemplate.query(sql, createRowMapper());
    }

    public List<LiepinApplication> findByStatus(String status) {
        String sql = "SELECT * FROM liepin_applications WHERE status = ? ORDER BY applied_at DESC";
        return jdbcTemplate.query(sql, createRowMapper(), status);
    }

    public boolean existsByJobId(String jobId) {
        String sql = "SELECT COUNT(*) FROM liepin_applications WHERE job_id = ? AND status = '已投递'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, jobId);
        return count != null && count > 0;
    }

    private RowMapper<LiepinApplication> createRowMapper() {
        return (ResultSet rs, int rowNum) -> LiepinApplication.builder()
            .id(rs.getString("id"))
            .jobId(rs.getString("job_id"))
            .jobKind(rs.getString("job_kind"))
            .jobTitle(rs.getString("job_title"))
            .company(rs.getString("company"))
            .city(rs.getString("city"))
            .salary(rs.getString("salary"))
            .status(rs.getString("status"))
            .appliedAt(rs.getTimestamp("applied_at").toLocalDateTime())
            .errorMessage(rs.getString("error_message"))
            .build();
    }
}
