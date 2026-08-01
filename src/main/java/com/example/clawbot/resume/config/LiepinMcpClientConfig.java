package com.example.clawbot.resume.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 为 Spring AI MCP 客户端添加猎聘认证，并限制模型可直接调用的工具。
 */
@Configuration
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "liepin")
public class LiepinMcpClientConfig {

    private static final Set<String> MODEL_VISIBLE_TOOLS = Set.of(
            "my-resume"
    );

    @Bean
    public McpSyncHttpClientRequestCustomizer liepinMcpAuthCustomizer(
            LiepinMcpTokenProvider tokenProvider
    ) {
        return (request, method, endpoint, body, context) ->
                request.header("x-user-token", tokenProvider.getToken());
    }

    @Bean
    public McpToolFilter liepinMcpToolFilter() {
        return (connection, tool) -> MODEL_VISIBLE_TOOLS.contains(tool.name());
    }
}
