package com.example.clawbot.config;

import com.example.clawbot.repository.ConversationRepository;
import com.example.clawbot.repository.MessageRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
// 应用启动时自动创建 SQLite 数据库表结构
@Component    //告诉Spring 这是一个组件
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @PostConstruct
    public void init() {
        log.info("初始化数据库表结构...");
        conversationRepository.createTable();
        messageRepository.createTable();
        log.info("数据库表初始化完成");
    }
}
