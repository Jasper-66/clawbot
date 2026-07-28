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

/** LLM Function Calling 工具，调用高德周边搜索 API 查询指定位置附近的 POI。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchNearbyTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${amap.api.key}")
    private String amapApiKey;

    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final int DEFAULT_RADIUS = 3000;
    private static final int MAX_RADIUS = 50000;
    private static final int MAX_RESULTS = 10;

    @Tool(name = "search_nearby", description = "搜索指定位置周边的POI（兴趣点），如餐厅、商店、景点等。若用户只提供地址名称，应先调用geocode工具获取坐标")
    public String searchNearby(
            @ToolParam(description = "中心点经纬度，格式 经度,纬度（如 116.473168,39.993015）") String location,
            @ToolParam(required = false, description = "搜索关键词，如 火锅、咖啡、游乐园") String keywords,
            @ToolParam(required = false, description = "POI类型编码，多个用英文逗号分隔") String types,
            @ToolParam(required = false, description = "搜索半径米，最大50000，默认3000") Integer radius,
            @ToolParam(required = false, description = "排序规则，distance=按距离排序，weight=综合排序") String sortrule) {
        if (location == null || location.isBlank()) {
            return "{\"error\":\"location 参数不能为空\"}";
        }
        if (!location.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
            return "{\"error\":\"location 格式不正确，应为 经度,纬度（如 116.473168,39.993015）\"}";
        }

        int r = DEFAULT_RADIUS;
        if (radius != null) {
            r = Math.max(1, Math.min(radius, MAX_RADIUS));
        }

        String sr = (sortrule != null && !sortrule.isBlank()) ? sortrule : "distance";
        if (!"distance".equals(sr) && !"weight".equals(sr)) {
            sr = "distance";
        }

        String kw = (keywords != null) ? keywords.trim() : "";
        String tp = (types != null) ? types.trim() : "";

        log.info("执行周边搜索: location={}, keywords={}, types={}, radius={}, sortrule={}",
                location, kw, tp, r, sr);

        try {
            URI uri = buildUri(location, kw, tp, r, sr);
            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);

            String status = root.path("status").asText("0");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("未知错误");
                return "{\"error\":\"搜索失败：" + info + "\"}";
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

            return objectMapper.writeValueAsString(Map.of(
                    "count", results.size(),
                    "results", results
            ));
        } catch (Exception e) {
            log.error("周边搜索工具执行失败: {}", e.getMessage());
            return "{\"error\":\"周边搜索请求异常\"}";
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
