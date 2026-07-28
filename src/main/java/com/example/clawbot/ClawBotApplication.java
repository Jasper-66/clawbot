package com.example.clawbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Spring Boot 启动类，启用定时任务调度
@SpringBootApplication
@EnableScheduling
public class ClawBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClawBotApplication.class, args);
    }

}
