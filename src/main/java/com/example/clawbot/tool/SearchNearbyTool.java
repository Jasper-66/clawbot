package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 搜索指定位置周边的 POI（兴趣点）。
 * 调用高德地图周边搜索 API，返回格式化结果供大模型使用。
 */
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
    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final int DEFAULT_RADIUS = 3000;
    private static final int MAX_RADIUS = 50000;
    private static final int MAX_RESULTS = 10;

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

    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String location = arguments.path("location").asText("").trim();
            if (location.isEmpty()) {
                return "工具调用失败：location 参数不能为空";
            }
            if (!location.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
                return "工具调用失败：location 格式不正确，应为 经度,纬度（如 116.473168,39.993015）";
            }

            String keywords = arguments.path("keywords").asText("").trim();
            String types = arguments.path("types").asText("").trim();

            int radius = DEFAULT_RADIUS;
            if (arguments.has("radius") && !arguments.path("radius").isNull()) {
                radius = arguments.path("radius").asInt(DEFAULT_RADIUS);
                if (radius < 1) radius = 1;
                if (radius > MAX_RADIUS) radius = MAX_RADIUS;
            }

            String sortrule = arguments.path("sortrule").asText("distance").trim();
            if (!"distance".equals(sortrule) && !"weight".equals(sortrule)) {
                sortrule = "distance";
            }

            log.info("执行周边搜索: location={}, keywords={}, types={}, radius={}, sortrule={}",
                    location, keywords, types, radius, sortrule);

            URI uri = buildUri(location, keywords, types, radius, sortrule);
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

            List<Map<String, Object>> results = new java.util.ArrayList<>();
            int count = Math.min(pois.size(), MAX_RESULTS);
            for (int i = 0; i < count; i++) {
                JsonNode poi = pois.get(i);
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", poi.path("name").asText(""));
                item.put("type", poi.path("type").asText(""));
                item.put("address", poi.path("address").asText(""));
                item.put("location", poi.path("location").asText(""));
                item.put("tel", poi.path("tel").asText(""));
                item.put("distance", poi.path("distance").asText(""));
                results.add(item);
            }

            return objectMapper.writeValueAsString(Map.of(
                    "count", results.size(),
                    "results", results
            ));
        } catch (Exception e) {
            log.error("周边搜索工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON 或请求异常";
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
