package com.example.clawbot.config;

import com.example.clawbot.repository.ConversationRepository;
import com.example.clawbot.repository.MessageRepository;
import com.example.clawbot.repository.ReminderRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
// 应用启动时自动创建 SQLite 数据库表结构
@Component    //告诉Spring 这是一个组件
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ReminderRepository reminderRepository;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        log.info("初始化数据库表结构...");
        conversationRepository.createTable();
        messageRepository.createTable();
        reminderRepository.createTable();
        createUserProfilesTable();
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
                skills           TEXT,
                summary          TEXT,
                raw_resume_text  TEXT,
                created_at       TEXT,
                updated_at       TEXT
            )
            """);
        log.info("user_profiles 表创建完成");
    }
}
