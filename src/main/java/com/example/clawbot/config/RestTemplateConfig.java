package com.example.clawbot.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

// RestTemplate Bean 配置，提供 HTTP 客户端
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Vision API 专用 ChatModel（DashScope 多模态模型）。
     *
     * <p>与主 ChatModel（DeepSeek）分离，因为 Vision 使用不同的 API Key、Base URL 和模型。</p>
     */
    @Bean
    public OpenAiChatModel visionChatModel(
            @Value("${vision.api.key}") String apiKey,
            @Value("${vision.api.base-url}") String baseUrl,
            @Value("${vision.api.model}") String model) {

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
