package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 周边搜索工具：LLM 可调用搜索指定位置附近的 POI 兴趣点（高德地图 API）
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchNearbyTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${amap.api.key}")
    private String amapApiKey;

    private static final String NAME = "search_nearby";
    private static final String DESCRIPTION = "搜索指定位置周边的 POI（兴趣点），如餐厅、商店、景点等。location 参数必须为 经度,纬度 格式，若用户提供的是地址名称，应先调用 geocode 工具获取坐标。";
    private static final int DEFAULT_RADIUS = 3000;
    /** 最大搜索半径（米） */
    private static final int MAX_RADIUS = 50000;
    /** 单次最多返回的 POI 数量，防止上下文超限 */
    private static final int MAX_RESULTS = 10;
    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "search_nearby"
     */
    public String getToolName() {
        return NAME;
    }

    @Tool(name = "search_nearby", description = "搜索指定位置周边的 POI（兴趣点），如餐厅、商店、景点等。location 参数必须为 经度,纬度 格式，若用户提供的是地址名称，应先调用 geocode 工具获取坐标。")
    public String searchNearby(
            @ToolParam(description = "中心点经纬度，格式 经度,纬度（如 116.473168,39.993015）。若用户只提供地址，应先调用 geocode 工具转换为此格式。") String location,
            @ToolParam(required = false, description = "搜索关键词，如 火锅、咖啡、游乐园") String keywords,
            @ToolParam(required = false, description = "POI 类型编码，多个用英文逗号 , 分隔，如 050301,050302") String types,
            @ToolParam(required = false, description = "搜索半径，单位米，最大 50000，默认 3000") Integer radius,
            @ToolParam(required = false, description = "排序规则：distance=按距离排序（默认），weight=综合排序") String sortrule) {

        if (location == null || location.trim().isEmpty()) {
            return "工具调用失败：location 参数不能为空";
        }
        String trimmedLocation = location.trim();
        if (!trimmedLocation.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
            return "工具调用失败：location 格式不正确，应为 经度,纬度（如 116.473168,39.993015）";
        }

        int effectiveRadius = DEFAULT_RADIUS;
        if (radius != null) {
            effectiveRadius = radius;
            if (effectiveRadius < 1) effectiveRadius = 1;
            if (effectiveRadius > MAX_RADIUS) effectiveRadius = MAX_RADIUS;
        }

        String effectiveSortrule = (sortrule != null && sortrule.trim().equals("weight")) ? "weight" : "distance";
        String effectiveKeywords = keywords != null ? keywords.trim() : "";
        String effectiveTypes = types != null ? types.trim() : "";

        log.info("[行动] LLM调用工具: search_nearby(location=\"{}\", keywords=\"{}\", radius={}) → 搜索周边POI",
                trimmedLocation, effectiveKeywords, effectiveRadius);

        try {
            URI uri = buildUri(trimmedLocation, effectiveKeywords, effectiveTypes, effectiveRadius, effectiveSortrule);
            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);

            String status = root.path("status").asText("0");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("未知错误");
                return String.format("{\"error\":\"搜索失败：%s\"}", info);
            }

            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return "{\"count\":0,\"results\":[],\"message\":\"附近未找到匹配的地点\"}";
            }

            List<Map<String, Object>> results = new ArrayList<>();
            int count = Math.min(pois.size(), MAX_RESULTS);
            for (int i = 0; i < count; i++) {
                JsonNode poi = pois.get(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", poi.path("name").asText(""));
                item.put("type", poi.path("type").asText(""));
                item.put("address", poi.path("address").asText(""));
                item.put("location", poi.path("location").asText(""));
                item.put("tel", poi.path("tel").asText(""));
                item.put("distance", poi.path("distance").asText(""));
                results.add(item);
            }

            log.info("[观察] 工具返回: search_nearby → 找到 {} 个周边地点", results.size());
            return objectMapper.writeValueAsString(Map.of(
                    "count", results.size(),
                    "results", results
            ));
        } catch (Exception e) {
            log.error("[异常] 工具调用失败 search_nearby(location=\"{}\") | 原因: {} | 建议: 检查高德地图 API 密钥",
                    trimmedLocation, effectiveKeywords, e.getMessage(), e);
            return "工具调用失败：周边搜索异常: " + e.getMessage();
        }
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * @return Function Calling 格式的工具定义 Map
     */
    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "location", Map.of(
                                                "type", "string",
                                                "description", "中心点经纬度，格式 经度,纬度（如 116.473168,39.993015）。若用户只提供地址，应先调用 geocode 工具转换为此格式。"
                                        ),
                                        "keywords", Map.of(
                                                "type", "string",
                                                "description", "搜索关键词，如 火锅、咖啡、游乐园"
                                        ),
                                        "types", Map.of(
                                                "type", "string",
                                                "description", "POI 类型编码，多个用英文逗号 , 分隔，如 050301,050302"
                                        ),
                                        "radius", Map.of(
                                                "type", "integer",
                                                "description", "搜索半径，单位米，最大 50000，默认 3000"
                                        ),
                                        "sortrule", Map.of(
                                                "type", "string",
                                                "description", "排序规则：distance=按距离排序（默认），weight=综合排序",
                                                "enum", List.of("distance", "weight")
                                        )
                                ),
                                "required", List.of("location")
                        )
                )
        );
    }

    /**
     * 校验并执行模型返回的工具调用。
     *
     * @param functionName  工具名称
     * @param argumentsJson LLM 生成的参数 JSON
     * @return 周边 POI 列表 JSON（包含 count 和 results 数组），或错误信息
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }
        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String location = arguments.path("location").asText("").trim();
            String keywords = arguments.path("keywords").asText("").trim();
            String types = arguments.path("types").asText("").trim();
            int radius = arguments.path("radius").asInt(DEFAULT_RADIUS);
            String sortrule = arguments.path("sortrule").asText("distance").trim();
            return searchNearby(location, keywords, types, radius, sortrule);
        } catch (Exception e) {
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }

    private URI buildUri(String location, String keywords, String types, int radius, String sortrule) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(AMAP_PLACE_AROUND_URL)
                .queryParam("key", amapApiKey)
                .queryParam("location", location)
                .queryParam("radius", radius)
                .queryParam("sortrule", sortrule)
                .queryParam("offset", MAX_RESULTS)
                .queryParam("page", 1)
                .queryParam("extensions", "base")
                .encode(StandardCharsets.UTF_8);

        if (!keywords.isEmpty()) {
            builder.queryParam("keywords", keywords);
        }
        if (!types.isEmpty()) {
            builder.queryParam("types", types);
        }

        return builder.build().toUri();
    }
}
