package com.example.clawbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ClawBot 微信机器人应用入口。
 *
 * <p>基于 Spring Boot 3.2 构建，集成 LLM 对话、图片识别、语音合成/识别（TTS/ASR）、
 * 图片生成、天气查询、文件总结等功能，通过 WeChat ILink SDK 与微信服务器通信。</p>
 *
 * <h3>启动流程</h3>
 * <ol>
 *   <li>{@code SpringApplication.run()} 初始化 Spring 容器，自动扫描并注册所有 Bean</li>
 *   <li>{@link com.example.clawbot.service.WeChatBotService#init()} 通过
 *       {@link jakarta.annotation.PostConstruct @PostConstruct} 异步启动微信登录</li>
 *   <li>登录成功后开始 2 秒间隔的消息轮询，分发到各业务 Service 处理</li>
 * </ol>
 *
 * <p>{@code @SpringBootApplication} 等价于同时启用：
 * {@code @Configuration}、{@code @EnableAutoConfiguration}、{@code @ComponentScan}，
 * 自动装配范围为此类所在包及子包（{@code com.example.clawbot}）。</p>
 *
 * @see com.example.clawbot.service.WeChatBotService
 * @see com.example.clawbot.service.LlmService
 * @see com.example.clawbot.service.SpeechService
 */
@SpringBootApplication
public class ClawBotApplication {

    /**
     * 应用主入口方法。
     *
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
