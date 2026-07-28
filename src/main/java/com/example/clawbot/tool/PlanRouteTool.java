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

/** LLM Function Calling 工具，调用高德驾车路径规划 API 计算导航路线。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanRouteTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${amap.api.key}")
    private String amapApiKey;

    private static final String AMAP_DIRECTION_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final int DEFAULT_RADIUS = 3000;
    private static final int MAX_RADIUS = 50000;

    @Tool(name = "plan_route", description = "规划从起点到终点的驾车路线。若用户只提供地址，应先调用geocode工具转换")
    public String planRoute(
            @ToolParam(description = "起点经纬度，格式 经度,纬度") String location,
            @ToolParam(required = false, description = "终点POI关键词，如 火锅、咖啡") String keywords,
            @ToolParam(required = false, description = "POI类型编码") String types,
            @ToolParam(required = false, description = "搜索半径米，默认3000") Integer radius,
            @ToolParam(required = false, description = "distance或weight") String sortrule,
            @ToolParam(required = false, description = "终点经纬度 经度,纬度，若提供则直接规划") String destination) {
        try {
            // 1. 校验起点
            if (location == null || location.isBlank()) {
                return "{\"error\":\"location 参数不能为空\"}";
            }
            if (!location.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
                return "{\"error\":\"location 格式不正确，应为 经度,纬度\"}";
            }

            String dest = (destination != null && !destination.isBlank()) ? destination.trim() : "";

            // 2. 如果没有直接指定终点，通过周边搜索确定
            if (dest.isEmpty()) {
                String kw = (keywords != null) ? keywords.trim() : "";
                String tp = (types != null) ? types.trim() : "";

                if (kw.isEmpty() && tp.isEmpty()) {
                    return "{\"error\":\"keywords、types 或 destination 参数至少需要提供一个\"}";
                }

                int r = DEFAULT_RADIUS;
                if (radius != null) {
                    r = Math.max(1, Math.min(radius, MAX_RADIUS));
                }

                String sr = (sortrule != null && !sortrule.isBlank()) ? sortrule : "distance";
                if (!"distance".equals(sr) && !"weight".equals(sr)) {
                    sr = "distance";
                }

                dest = searchDestination(location, kw, tp, r, sr);
                if (dest == null) {
                    return "{\"error\":\"未找到匹配的终点地点\"}";
                }
            } else {
                if (!dest.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
                    return "{\"error\":\"destination 格式不正确，应为 经度,纬度\"}";
                }
            }

            log.info("执行路线规划: origin={}, destination={}", location, dest);
            return planRoute(location, dest);
        } catch (Exception e) {
            log.error("路线规划工具执行失败: {}", e.getMessage());
            return "{\"error\":\"路线规划异常\"}";
        }
    }

    private String searchDestination(String origin, String keywords, String types, int radius, String sortrule) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(AMAP_PLACE_AROUND_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("location", origin)
                    .queryParam("radius", radius)
                    .queryParam("sortrule", sortrule)
                    .queryParam("offset", 1)
                    .queryParam("page", 1)
                    .queryParam("extensions", "base")
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            if (!keywords.isEmpty()) {
                uri = UriComponentsBuilder.fromUri(uri)
                        .queryParam("keywords", keywords)
                        .build().toUri();
            } else if (!types.isEmpty()) {
                uri = UriComponentsBuilder.fromUri(uri)
                        .queryParam("types", types)
                        .build().toUri();
            }

            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (!"1".equals(root.path("status").asText("0"))) {
                return null;
            }

            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return null;
            }

            return pois.get(0).path("location").asText("");
        } catch (Exception e) {
            log.error("搜索终点失败: {}", e.getMessage());
            return null;
        }
    }

    private String planRoute(String origin, String destination) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(AMAP_DIRECTION_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .queryParam("extensions", "all")
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);

            String status = root.path("status").asText("0");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("未知错误");
                return "{\"error\":\"路线规划失败：" + info + "\"}";
            }

            JsonNode route = root.path("route");
            JsonNode paths = route.path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                return "{\"error\":\"未找到可用路线\"}";
            }

            JsonNode path = paths.get(0);
            String distance = path.path("distance").asText("");
            String duration = path.path("duration").asText("");

            List<Map<String, Object>> steps = new ArrayList<>();
            JsonNode stepsNode = path.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode step : stepsNode) {
                    Map<String, Object> stepItem = new LinkedHashMap<>();
                    stepItem.put("instruction", step.path("instruction").asText(""));
                    stepItem.put("distance", step.path("distance").asText(""));
                    stepItem.put("duration", step.path("duration").asText(""));
                    stepItem.put("action", step.path("action").asText(""));
                    steps.add(stepItem);
                }
            }

            return objectMapper.writeValueAsString(Map.of(
                    "distance", distance,
                    "duration", duration,
                    "steps", steps
            ));
        } catch (Exception e) {
            log.error("路线规划请求失败: {}", e.getMessage());
            return "{\"error\":\"路线规划请求异常\"}";
        }
    }
}
