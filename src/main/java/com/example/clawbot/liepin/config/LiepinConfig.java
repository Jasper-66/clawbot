package com.example.clawbot.liepin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 猎聘 MCP 接口配置。
 *
 * <p>从 application.properties 中读取猎聘 MCP 服务的 endpoint、token 等配置项。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.liepin")
public class LiepinConfig {

    /** 是否启用猎聘 MCP 服务 */
    private boolean enabled;

    /** MCP 服务端点地址 */
    private String endpoint;

    /** MCP 鉴权 Token */
    private String token;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
