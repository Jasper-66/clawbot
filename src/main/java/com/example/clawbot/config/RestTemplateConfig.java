package com.example.clawbot.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端配置模块。
 *
 * <p>提供 {@link RestTemplate} Bean，作为项目中所有外部 API 调用的统一 HTTP 客户端。
 * 被各 Service 类通过构造器注入使用：{@code WeatherService}、{@code LlmService}、
 * {@code SpeechService}、{@code FileSummaryService} 等。</p>
 *
 * <h3>设计说明</h3>
 * <p>当前为最小化配置，使用 {@code RestTemplate} 默认构造器。默认行为：</p>
 * <ul>
 *   <li>连接通过 {@code HttpURLConnection}（JDK 内置，无需额外依赖）</li>
 *   <li>无连接池 — 每次请求新建 TCP 连接，适合低并发场景</li>
 *   <li>无超时设置 — 依赖操作系统默认超时，生产环境建议显式配置</li>
 *   <li>无重试机制 — 失败即抛异常，由各 Service 自行处理</li>
 * </ul>
 *
 * <h3>扩展方向</h3>
 * <p>如需生产级特性，可替换为：</p>
 * <ul>
 *   <li>使用 {@code HttpComponentsClientHttpRequestFactory} 启用 Apache HttpClient 连接池</li>
 *   <li>设置 {@code connectTimeout} / {@code readTimeout} 控制请求超时</li>
 *   <li>添加 {@code ClientHttpRequestInterceptor} 实现统一日志/认证/重试</li>
 *   <li>迁移到 {@code RestClient}（Spring 6.1+ 推荐的同步 HTTP 客户端）</li>
 * </ul>
 *
 * @see org.springframework.web.client.RestTemplate
 * @see com.example.clawbot.service.WeatherService
 * @see com.example.clawbot.service.LlmService
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 创建 {@link RestTemplate} 单例 Bean。
     *
     * <p>RestTemplate 是线程安全的，因此在整个应用生命周期内共享一个实例即可。
     * Spring 默认以单例（singleton）作用域管理此 Bean。</p>
     *
     * <p>被注入的 Service 各自负责设置请求头（如 {@code Authorization}、{@code Content-Type}），
     * 而非在此处添加全局拦截器，因为不同 API 的认证方式各不相同
     * （DeepSeek 用 Bearer Token、心知天气用 query param key、高德用 query param key）。</p>
     *
     * @return 新创建的 {@link RestTemplate} 实例
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
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
