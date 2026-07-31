package com.example.clawbot.config;

import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class EmbeddingConfig {

    @Bean
    public OpenAiEmbeddingModel embeddingModel(
            @Value("${embedding.api.key}") String apiKey,
            @Value("${embedding.api.base-url}") String baseUrl,
            @Value("${embedding.api.model}") String model) {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .build();

        return new OpenAiEmbeddingModel(openAiApi, org.springframework.ai.document.MetadataMode.EMBED, options);
    }
}
