package com.example.clawbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Spring Boot 启动类，启用定时任务调度
@SpringBootApplication
@EnableScheduling
public class ClawBotApplication {

    /**
     * 应用主入口方法。
     * <p>调用 {@link SpringApplication#run(Class, String...)} 启动嵌入式 Tomcat 服务器，
     * 加载 ApplicationContext 并触发所有 {@code @Bean} 初始化和
     * {@link jakarta.annotation.PostConstruct @PostConstruct} 回调。</p>
     *
     * @param args 命令行参数，可传入 Spring Boot 配置覆盖项（如 {@code --server.port=8080}）
     */
    public static void main(String[] args) {
        SpringApplication.run(ClawBotApplication.class, args);
    }

}
