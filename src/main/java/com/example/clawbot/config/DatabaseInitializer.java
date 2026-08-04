package com.example.clawbot.config;

import com.example.clawbot.knowledge.repository.KnowledgeDocumentRepository;
import com.example.clawbot.repository.ConversationRepository;
import com.example.clawbot.repository.MessageRepository;
import com.example.clawbot.repository.ReminderRepository;
import com.example.clawbot.repository.TokenUsageRepository;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import com.example.clawbot.resume.repository.ApplicationSessionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ReminderRepository reminderRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final ApplicationRecordRepository applicationRecordRepository;
    private final ApplicationSessionRepository applicationSessionRepository;

    @PostConstruct
    public void init() {
        log.info("初始化数据库表结构...");
        conversationRepository.createTable();
        messageRepository.createTable();
        reminderRepository.createTable();
        knowledgeDocumentRepository.createTable();
        tokenUsageRepository.createTable();
        applicationRecordRepository.createTable();
        applicationSessionRepository.createTable();
        log.info("数据库表初始化完成");
    }

}
