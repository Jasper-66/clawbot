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

@Slf4j
@Component
@RequiredArgsConstructor
public class PlanRouteTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${amap.api.key}")
    private String amapApiKey;

    private static final String NAME = "plan_route";
    private static final String DESCRIPTION = "规划从起点到终点的路线。起点由 location 参数指定（经纬度格式），终点通过 keywords 或 types 搜索周边 POI 确定。若用户只提供地址，应先调用 geocode 工具转换为经纬度。";
    private static final String AMAP_DIRECTION_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final int DEFAULT_RADIUS = 3000;
    private static final int MAX_RADIUS = 50000;

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
                                                "description", "起点经纬度，格式 经度,纬度（如 116.473168,39.993015）。若用户只提供地址，应先调用 geocode 工具转换为此格式。"
                                        ),
                                        "keywords", Map.of(
                                                "type", "string",
                                                "description", "终点关键词，用于搜索周边 POI，如 火锅、咖啡、游乐园"
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
                                        ),
                                        "destination", Map.of(
                                                "type", "string",
                                                "description", "终点经纬度，格式 经度,纬度。若提供此参数，将直接规划路线，不再搜索周边 POI。"
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
            String origin = arguments.path("location").asText("").trim();
            if (origin.isEmpty()) {
                return "工具调用失败：location 参数不能为空";
            }
            if (!origin.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
                return "工具调用失败：location 格式不正确，应为 经度,纬度（如 116.473168,39.993015）";
            }

            String destination = arguments.path("destination").asText("").trim();

            if (destination.isEmpty()) {
                String keywords = arguments.path("keywords").asText("").trim();
                String types = arguments.path("types").asText("").trim();

                if (keywords.isEmpty() && types.isEmpty()) {
                    return "工具调用失败：keywords、types 或 destination 参数至少需要提供一个";
                }

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

                destination = searchDestination(origin, keywords, types, radius, sortrule);
                if (destination == null) {
                    return "{\"error\":\"未找到匹配的终点地点\"}";
                }
            } else {
                if (!destination.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
                    return "工具调用失败：destination 格式不正确，应为 经度,纬度";
                }
            }

            log.info("执行路线规划: origin={}, destination={}", origin, destination);
            return planRoute(origin, destination);
        } catch (Exception e) {
            log.error("路线规划工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON 或请求异常";
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
                        .build()
                        .toUri();
            } else if (!types.isEmpty()) {
                uri = UriComponentsBuilder.fromUri(uri)
                        .queryParam("types", types)
                        .build()
                        .toUri();
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
                return String.format("{\"error\":\"路线规划失败：%s\"}", info);
            }

            JsonNode route = root.path("route");
            JsonNode paths = route.path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                return "{\"error\":\"未找到可用路线\"}";
            }

            JsonNode path = paths.get(0);
            String distance = path.path("distance").asText("");
            String duration = path.path("duration").asText("");

            List<Map<String, Object>> steps = new java.util.ArrayList<>();
            JsonNode stepsNode = path.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode step : stepsNode) {
                    Map<String, Object> stepItem = new java.util.LinkedHashMap<>();
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
