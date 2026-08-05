package com.example.clawbot.config;

import com.example.clawbot.knowledge.repository.KnowledgeDocumentRepository;
import com.example.clawbot.repository.ConversationRepository;
import com.example.clawbot.repository.MessageRepository;
import com.example.clawbot.repository.ReminderRepository;
import com.example.clawbot.repository.TokenUsageRepository;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ReminderRepository reminderRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationRecordRepository applicationRecordRepository;

    @PostConstruct
    public void init() {
        log.info("初始化数据库表结构...");
        conversationRepository.createTable();
        messageRepository.createTable();
        reminderRepository.createTable();
        knowledgeDocumentRepository.createTable();
        tokenUsageRepository.createTable();
        createUserProfilesTable();
        applicationRecordRepository.createTable();
        log.info("数据库表初始化完成");
    }

    private void createUserProfilesTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_profiles (
                user_id          TEXT PRIMARY KEY,
                name             TEXT,
                phone            TEXT,
                email            TEXT,
                desired_position TEXT,
                desired_city     TEXT,
                salary_range     TEXT,
                experience_years INTEGER,
                education        TEXT,
                school           TEXT,
                school_tier      TEXT,
                skills           TEXT,
                summary          TEXT,
                raw_resume_text  TEXT,
                created_at       TEXT,
                updated_at       TEXT
            )
            """);
        // 兼容旧库：已存在但缺列时补列
        addColumnIfMissing("user_profiles", "school", "TEXT");
        addColumnIfMissing("user_profiles", "school_tier", "TEXT");
        log.info("user_profiles 表创建完成");
    }

    private void addColumnIfMissing(String table, String column, String type) {
        List<Map<String, Object>> cols = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
        boolean exists = cols.stream().anyMatch(c -> column.equals(c.get("name")));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("数据库表 {} 已补列 {}", table, column);
        }
    }
}
