package com.example.clawbot.resume.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 简历投递平台配置 — 在 application.properties 中配置
@Configuration
@ConfigurationProperties(prefix = "resume.platform")
public class ResumePlatformConfig {

    /** 平台选择: boss / lagou / zhilian / mock */
    private String provider = "mock";

    /** 平台 API 基础地址 */
    private String baseUrl;

    /** 平台 API Key */
    private String apiKey;

    /** 默认城市 */
    private String defaultCity = "杭州";

    /** 最低匹配分（0~100），低于此分的岗位不投递 */
    private int minMatchScore = 60;

    /** 单次最大投递数 */
    private int maxApplyCount = 10;

    /** 投递间隔最小秒数（防封） */
    private int minApplyInterval = 2;

    /** 投递间隔最大秒数（防封） */
    private int maxApplyInterval = 5;

    /** 浏览器是否无头模式（true=不显示浏览器窗口） */
    private boolean browserHeadless = false;

    /** 猎聘 Token（MCP API 认证） */
    private String liepinToken;

    /** 猎聘 Cookie（网页 API 认证） */
    private String liepinCookie;

    // getters / setters
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getDefaultCity() { return defaultCity; }
    public void setDefaultCity(String defaultCity) { this.defaultCity = defaultCity; }
    public int getMinMatchScore() { return minMatchScore; }
    public void setMinMatchScore(int minMatchScore) { this.minMatchScore = minMatchScore; }
    public int getMaxApplyCount() { return maxApplyCount; }
    public void setMaxApplyCount(int maxApplyCount) { this.maxApplyCount = maxApplyCount; }
    public int getMinApplyInterval() { return minApplyInterval; }
    public void setMinApplyInterval(int minApplyInterval) { this.minApplyInterval = minApplyInterval; }
    public int getMaxApplyInterval() { return maxApplyInterval; }
    public void setMaxApplyInterval(int maxApplyInterval) { this.maxApplyInterval = maxApplyInterval; }
    public boolean isBrowserHeadless() { return browserHeadless; }
    public void setBrowserHeadless(boolean browserHeadless) { this.browserHeadless = browserHeadless; }
    public String getLiepinToken() { return liepinToken; }
    public void setLiepinToken(String liepinToken) { this.liepinToken = liepinToken; }
    public String getLiepinCookie() { return liepinCookie; }
    public void setLiepinCookie(String liepinCookie) { this.liepinCookie = liepinCookie; }
}
