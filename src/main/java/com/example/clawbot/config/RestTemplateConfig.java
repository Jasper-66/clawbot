package com.example.clawbot.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP 客户端配置模块。
 *
 * <p>提供 {@link RestTemplate} Bean，作为项目中所有外部 API 调用的统一 HTTP 客户端。
 * 被各 Service 类通过构造器注入使用：{@code WeatherService}、{@code LlmService}、
 * {@code SpeechService}、{@code FileSummaryService} 等。</p>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>使用 {@code RestTemplateBuilder} 配置连接超时和读取超时，防止请求无限等待</li>
 *   <li>连接通过 {@code HttpURLConnection}（JDK 内置，无需额外依赖）</li>
 *   <li>无连接池 — 每次请求新建 TCP 连接，适合低并发场景</li>
 *   <li>无重试机制 — 失败即抛异常，由各 Service 自行处理</li>
 * </ul>
 *
 * <h3>超时设置</h3>
 * <ul>
 *   <li>连接超时（connectTimeout）：10 秒 — 建立 TCP 连接的最大等待时间</li>
 *   <li>读取超时（readTimeout）：60 秒 — 服务器响应后，读取完整响应的最大等待时间</li>
 * </ul>
 * LLM 调用（尤其是多轮工具调用后的总结回复）可能较慢，需要较长的读取超时。
 *
 * <h3>扩展方向</h3>
 * <ul>
 *   <li>使用 {@code HttpComponentsClientHttpRequestFactory} 启用 Apache HttpClient 连接池</li>
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
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
