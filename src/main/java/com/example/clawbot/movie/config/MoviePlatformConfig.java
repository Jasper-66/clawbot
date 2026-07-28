package com.example.clawbot.movie.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 电影票平台配置 — 在 application.properties 中配置
@Configuration
@ConfigurationProperties(prefix = "movie.platform")
public class MoviePlatformConfig {

    /** 平台选择: taopiaopiao / maoyan / mock */
    private String provider = "mock";

    /** 平台 API 基础地址 */
    private String baseUrl;

    /** 平台 API Key */
    private String apiKey;

    /** 默认城市 */
    private String defaultCity = "北京";

    /** 座位锁定超时（秒） */
    private int lockTimeout = 300;

    /** 最大购票数量 */
    private int maxTickets = 6;

    // getters / setters 由 Lombok 待定或手写
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getDefaultCity() { return defaultCity; }
    public void setDefaultCity(String defaultCity) { this.defaultCity = defaultCity; }
    public int getLockTimeout() { return lockTimeout; }
    public void setLockTimeout(int lockTimeout) { this.lockTimeout = lockTimeout; }
    public int getMaxTickets() { return maxTickets; }
    public void setMaxTickets(int maxTickets) { this.maxTickets = maxTickets; }
}
