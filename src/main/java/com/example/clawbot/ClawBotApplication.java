package com.example.clawbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 应用入口，集成 LLM 对话、TTS/ASR、图片生成、天气查询等功能。 */
@SpringBootApplication
public class ClawBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClawBotApplication.class, args);
    }

}
