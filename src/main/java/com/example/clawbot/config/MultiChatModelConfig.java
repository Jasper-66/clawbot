package com.example.clawbot.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 多 ChatModel 配置：DeepSeek（对话+工具调用）和 DashScope（Vision 图片识别）。
 * <p>
 * 两者都是 OpenAI 兼容 API，使用 OpenAiChatModel 统一接入。
 * 复用已有的 deepseek.api.* 和 vision.api.* 配置属性。
 */
@Configuration
public class MultiChatModelConfig {

    private static RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient));
    }

    /** DeepSeek ChatModel — 备用模型（余额不足时可手动切换） */
    @Bean("deepSeekChatModel")
    public OpenAiChatModel deepSeekChatModel(
            @Value("${deepseek.api.key}") String apiKey,
            @Value("${deepseek.api.base-url}") String baseUrl,
            @Value("${deepseek.api.model}") String model) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .restClientBuilder(restClientBuilder())
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.7)
                        .maxTokens(1024)
                        .build())
                .build();
    }

    /** DashScope ChatModel — 主模型，用于对话和 Tool Calling（有免费额度） */
    @Primary
    @Bean("dashScopeChatModel")
    public OpenAiChatModel dashScopeChatModel(
            @Value("${vision.api.key}") String apiKey,
            @Value("${vision.api.base-url}") String baseUrl,
            @Value("${vision.api.model}") String model) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .restClientBuilder(restClientBuilder())
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .maxTokens(1024)
                        .build())
                .build();
    }
}
