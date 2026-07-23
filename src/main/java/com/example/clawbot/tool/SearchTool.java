package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时搜索工具 — LLM 可调用的网络搜索能力。
 *
 * <p>调用 Tavily Search API，专为 AI Agent 优化的搜索引擎。
 * 支持中文搜索，默认开启 AI 摘要，优先返回近期内容。</p>
 *
 * <h3>时效性优化</h3>
 * <p>当用户查询新闻、热点事件时，LLM 应设置 {@code days=1} 或 {@code topic="news"}
 * 以获取最新资讯。默认搜索深度为 {@code advanced}，确保结果全面。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${tavily.api.key:}")
    private String tavilyApiKey;

    private static final String NAME = "web_search";

    private static final String DESCRIPTION =
            "搜索互联网上的实时信息，包括新闻、热点事件、人物资讯等。" +
            "当用户询问最近发生的事件、需要最新数据的问题时，必须使用此工具。" +
            "查询新闻/热点时，务必设置 topic=\"news\" 且 days=1 或 days=3 以获取最新资讯。" +
            "支持中文搜索。";

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_MAX_RESULTS = 10;

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
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "搜索关键词，支持中文。例如：周杰伦最新消息、今日热点新闻"
                                        ),
                                        "topic", Map.of(
                                                "type", "string",
                                                "description", "搜索主题类型：news=优先新闻源（时效性最高），general=通用搜索。查询最新动态/热点/新闻时务必用 news",
                                                "enum", List.of("general", "news")
                                        ),
                                        "days", Map.of(
                                                "type", "integer",
                                                "description", "只返回最近 N 天内的结果。查询最新新闻/热点时建议设为 1 或 3，不设则不限时间"
                                        ),
                                        "search_depth", Map.of(
                                                "type", "string",
                                                "description", "搜索深度：advanced=深度搜索（结果更全更新），basic=快速搜索。默认 advanced",
                                                "enum", List.of("basic", "advanced")
                                        ),
                                        "max_results", Map.of(
                                                "type", "integer",
                                                "description", "返回结果数量。默认 5，最大 10"
                                        )
                                ),
                                "required", List.of("query")
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
            String query = arguments.path("query").asText("").trim();
            if (query.isEmpty()) {
                return "工具调用失败：query 参数不能为空";
            }
            if (query.length() > MAX_QUERY_LENGTH) {
                return "工具调用失败：query 参数过长（最大 " + MAX_QUERY_LENGTH + " 字符）";
            }

            String topic = arguments.path("topic").asText("general");
            if (!"general".equals(topic) && !"news".equals(topic)) {
                topic = "general";
            }


            int days = arguments.path("days").asInt(0);

            String searchDepth = arguments.path("search_depth").asText("advanced");
            if (!"basic".equals(searchDepth) && !"advanced".equals(searchDepth)) {
                searchDepth = "advanced";
            }

            int maxResults = arguments.path("max_results").asInt(DEFAULT_MAX_RESULTS);
            if (maxResults < 1) maxResults = 1;
            if (maxResults > MAX_MAX_RESULTS) maxResults = MAX_MAX_RESULTS;

            log.info("执行搜索工具: query={}, topic={}, days={}, depth={}", query, topic, days, searchDepth);

            // 构建 Tavily API 请求
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("api_key", tavilyApiKey);
            requestBody.put("query", query);
            requestBody.put("search_depth", searchDepth);
            requestBody.put("max_results", maxResults);
            requestBody.put("include_answer", "advanced");
            requestBody.put("include_raw_content", false);
            if (!"general".equals(topic)) {
                requestBody.put("topic", topic);
            }
            if (days > 0) {
                requestBody.put("days", days);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(requestBody), headers);

            String response = restTemplate.postForObject(TAVILY_API_URL, entity, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.has("error")) {
                log.error("Tavily API 错误: {}", root.path("error").asText());
                return "搜索失败：API 返回错误";
            }

            Map<String, Object> resultMap = new LinkedHashMap<>();

            if (root.hasNonNull("answer")) {
                resultMap.put("answer", root.path("answer").asText());
            }

            JsonNode results = root.path("results");
            List<Map<String, Object>> resultList = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode item : results) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("title", item.path("title").asText(""));
                    entry.put("url", item.path("url").asText(""));
                    entry.put("content", item.path("content").asText(""));
                    if (item.has("score")) {
                        entry.put("score", item.path("score").asDouble(0.0));
                    }
                    if (item.has("published_date")) {
                        entry.put("published_date", item.path("published_date").asText(""));
                    }
                    resultList.add(entry);
                }
            }
            resultMap.put("count", resultList.size());
            resultMap.put("results", resultList);

            return objectMapper.writeValueAsString(resultMap);
        } catch (Exception e) {
            log.error("搜索工具执行失败: {}", e.getMessage());
            return "工具调用失败：搜索请求异常";
        }
    }
}
