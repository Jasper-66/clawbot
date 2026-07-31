package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

/** LLM Function Calling 工具，调用 Tavily Search API 进行实时网络搜索。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${tavily.api.key:}")
    private String tavilyApiKey;

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_MAX_RESULTS = 10;

    @Tool(name = "web_search", description = "搜索互联网上的实时信息，包括新闻、热点事件、人物资讯等。当用户询问最近发生的事件、需要最新数据的问题时必须使用此工具。查询新闻/热点时务必设置topic=news且days=1或days=3。支持中文搜索")
    public String webSearch(
            @ToolParam(description = "搜索关键词") String query,
            @ToolParam(required = false, description = "搜索主题，news=优先新闻源，general=通用搜索") String topic,
            @ToolParam(required = false, description = "最近N天内，查询新闻时建议1或3") Integer days,
            @ToolParam(required = false, description = "advanced=深度搜索，basic=快速搜索") String search_depth,
            @ToolParam(required = false, description = "返回结果数量，默认5，最大10") Integer max_results) {
        if (query == null || query.isBlank()) {
            return "{\"error\":\"query 参数不能为空\"}";
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            return "{\"error\":\"query 参数过长\"}";
        }

        String t = "general";
        if (topic != null && !topic.isBlank()) {
            t = ("news".equals(topic)) ? "news" : "general";
        }

        int d = (days != null) ? days : 0;

        String depth = "advanced";
        if (search_depth != null && !search_depth.isBlank()) {
            depth = ("basic".equals(search_depth)) ? "basic" : "advanced";
        }

        int maxR = DEFAULT_MAX_RESULTS;
        if (max_results != null) {
            maxR = Math.max(1, Math.min(max_results, MAX_MAX_RESULTS));
        }

        log.info("执行搜索工具: query={}, topic={}, days={}, depth={}", query, t, d, depth);

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("api_key", tavilyApiKey);
            requestBody.put("query", query.trim());
            requestBody.put("search_depth", depth);
            requestBody.put("max_results", maxR);
            requestBody.put("include_answer", "advanced");
            requestBody.put("include_raw_content", false);
            if (!"general".equals(t)) {
                requestBody.put("topic", t);
            }
            if (d > 0) {
                requestBody.put("days", d);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(requestBody), headers);

            String response = restTemplate.postForObject(TAVILY_API_URL, entity, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.has("error")) {
                log.error("Tavily API 错误: {}", root.path("error").asText());
                return "{\"error\":\"搜索失败：API 返回错误\"}";
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
            return "{\"error\":\"搜索请求异常\"}";
        }
    }
}
