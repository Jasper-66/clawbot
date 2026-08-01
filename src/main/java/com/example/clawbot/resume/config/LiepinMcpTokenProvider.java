package com.example.clawbot.resume.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 统一读取猎聘授权 Token，优先使用项目配置，其次读取 CLI 配置。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "liepin")
public class LiepinMcpTokenProvider {

    private final LiepinMcpProperties properties;
    private final ObjectMapper objectMapper;

    public String getToken() {
        if (hasText(properties.getToken())) {
            return properties.getToken().trim();
        }

        String environmentToken = System.getenv("LIEPIN_USER_TOKEN");
        if (hasText(environmentToken)) {
            return environmentToken.trim();
        }

        for (Path cliConfig : tokenConfigPaths()) {
            if (Files.isRegularFile(cliConfig)) {
                try {
                    var config = objectMapper.readTree(cliConfig.toFile());
                    String token = config.path("user_token").asText("");
                    if (!hasText(token)) {
                        token = config.path("token").asText("");
                    }
                    if (hasText(token)) {
                        return token.trim();
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException("无法读取猎聘 CLI 授权配置: " + cliConfig, exception);
                }
            }
        }

        throw new IllegalStateException(
                "未配置猎聘 Token，请设置 LIEPIN_USER_TOKEN 或先执行 liepin-cli setup"
        );
    }

    private List<Path> tokenConfigPaths() {
        String configuredRoot = System.getenv("XDG_CONFIG_HOME");
        Path configRoot = hasText(configuredRoot)
                ? Path.of(configuredRoot)
                : Path.of(System.getProperty("user.home"), ".config");
        return List.of(
                configRoot.resolve("liepin-mcp").resolve("config.json"),
                configRoot.resolve("liepin-cli").resolve("config.json")
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
