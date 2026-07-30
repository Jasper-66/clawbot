package com.example.clawbot.tool;

import com.example.clawbot.service.NewsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsTool {

    private final NewsService newsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "get_news";
    private static final String DESCRIPTION = "查询各大平台的实时热搜榜单和新闻资讯。支持的平台：weibo（微博热搜）、toutiao（今日头条）、zhihu（知乎热榜）、baidu（百度热搜）、douyin（抖音热榜）、60s（60秒读懂世界）。当用户询问热搜、新闻、热榜、头条、今日大事等信息时使用此工具。";

    public String getToolName() {
        return NAME;
    }

    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "source", Map.of(
                                                "type", "string",
                                                "description", "新闻来源平台",
                                                "enum", List.of("weibo", "toutiao", "zhihu", "baidu", "douyin", "60s")
                                        ),
                                        "limit", Map.of(
                                                "type", "integer",
                                                "description", "返回的新闻条数，默认10，最大30"
                                        )
                                ),
                                "required", List.of("source")
                        )
                )
        );
    }

    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String source = arguments.path("source").asText("").trim().toLowerCase();
            if (source.isEmpty()) {
                return "工具调用失败：source 参数不能为空";
            }

            int limit = arguments.path("limit").isMissingNode()
                    ? 10
                    : arguments.path("limit").asInt(10);

            log.info("执行新闻工具: source={}, limit={}", source, limit);
            return newsService.getNews(source, limit);
        } catch (Exception e) {
            log.error("新闻工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
