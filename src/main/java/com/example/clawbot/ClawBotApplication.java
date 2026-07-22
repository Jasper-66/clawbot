package com.example.clawbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClawBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClawBotApplication.class, args);
    }

}
