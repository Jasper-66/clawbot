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

// 路线规划工具：LLM 可调用规划两点间的出行路线和距离耗时（高德地图 API）
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanRouteTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${amap.api.key}")
    private String amapApiKey;

    private static final String NAME = "plan_route";
    private static final String DESCRIPTION = "规划从起点到终点的驾车路线。起点由 location 参数指定（经纬度格式），终点通过 keywords 或 types 搜索周边 POI 确定或直接指定 destination。若用户只提供地址，应先调用 geocode 工具转换为经纬度。";
    private static final int DEFAULT_RADIUS = 3000;
    /** 最大搜索半径（米），防止无意义的大范围搜索 */
    private static final int MAX_RADIUS = 50000;
    private static final String AMAP_DIRECTION_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "plan_route"
     */
    public String getToolName() {
        return NAME;
    }

    @Tool(name = "plan_route", description = "规划从起点到终点的驾车路线。起点由 location 参数指定（经纬度格式），终点通过 keywords 或 types 搜索周边 POI 确定或直接指定 destination。若用户只提供地址，应先调用 geocode 工具转换为经纬度。")
    public String planRoute(
            @ToolParam(description = "起点经纬度，格式 经度,纬度（如 116.473168,39.993015）。若用户只提供地址，应先调用 geocode 工具转换为此格式。") String location,
            @ToolParam(required = false, description = "终点关键词，用于搜索周边 POI，如 火锅、咖啡、游乐园") String keywords,
            @ToolParam(required = false, description = "POI 类型编码，多个用英文逗号 , 分隔，如 050301,050302") String types,
            @ToolParam(required = false, description = "搜索半径，单位米，最大 50000，默认 3000") Integer radius,
            @ToolParam(required = false, description = "排序规则：distance=按距离排序（默认），weight=综合排序") String sortrule,
            @ToolParam(required = false, description = "终点经纬度，格式 经度,纬度。若提供此参数，将直接规划路线，不再搜索周边 POI。") String destination) {

        if (location == null || location.trim().isEmpty()) {
            return "工具调用失败：location 参数不能为空";
        }
        String origin = location.trim();
        if (!origin.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
            return "工具调用失败：location 格式不正确，应为 经度,纬度（如 116.473168,39.993015）";
        }

        String effectiveDestination = destination != null ? destination.trim() : "";

        try {
            if (effectiveDestination.isEmpty()) {
                String effectiveKeywords = keywords != null ? keywords.trim() : "";
                String effectiveTypes = types != null ? types.trim() : "";

                if (effectiveKeywords.isEmpty() && effectiveTypes.isEmpty()) {
                    return "工具调用失败：keywords、types 或 destination 参数至少需要提供一个";
                }

                int effectiveRadius = DEFAULT_RADIUS;
                if (radius != null) {
                    effectiveRadius = radius;
                    if (effectiveRadius < 1) effectiveRadius = 1;
                    if (effectiveRadius > MAX_RADIUS) effectiveRadius = MAX_RADIUS;
                }

                String effectiveSortrule = (sortrule != null && sortrule.trim().equals("weight")) ? "weight" : "distance";

                effectiveDestination = searchDestination(origin, effectiveKeywords, effectiveTypes, effectiveRadius, effectiveSortrule);
                if (effectiveDestination == null) {
                    return "{\"error\":\"未找到匹配的终点地点\"}";
                }
            } else {
                if (!effectiveDestination.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
                    return "工具调用失败：destination 格式不正确，应为 经度,纬度";
                }
            }

            log.info("[行动] LLM调用工具: plan_route(origin=\"{}\", destination=\"{}\") → 调用高德地图规划出行路线", origin, effectiveDestination);
            String result = planRoute(origin, effectiveDestination);
            log.info("[观察] 工具返回: plan_route → 路线规划完成");
            return result;
        } catch (Exception e) {
            log.error("[观察] 调用失败 plan_route(): origin={}, destination={}, 原因: {}, 建议: 检查高德地图 API 密钥和参数格式",
                    origin, effectiveDestination, e.getMessage(), e);
            return "工具调用失败：路线规划异常: " + e.getMessage();
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

    /**
     * 校验并执行模型返回的工具调用。
     *
     * @param functionName  工具名称
     * @param argumentsJson LLM 生成的参数 JSON
     * @return 路线规划结果 JSON（包含 distance、duration、steps），或错误信息
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
            String destination = arguments.path("destination").asText("").trim();
            return planRoute(location, keywords, types, radius, sortrule, destination);
        } catch (Exception e) {
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }

    private String searchDestination(String origin, String keywords, String types, int radius, String sortrule) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(AMAP_PLACE_AROUND_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("location", origin)
                    .queryParam("radius", radius)
                    .queryParam("sortrule", sortrule)
                    .queryParam("offset", 1)
                    .queryParam("page", 1)
                    .queryParam("extensions", "base")
                    .encode(StandardCharsets.UTF_8);

            if (!keywords.isEmpty()) {
                builder.queryParam("keywords", keywords);
            } else if (!types.isEmpty()) {
                builder.queryParam("types", types);
            }

            URI uri = builder.build().toUri();
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
            log.error("[观察] 调用失败 plan_route(搜索终点): origin={}, keywords={}, 原因: {}, 建议: 检查搜索参数或 API 密钥",
                    origin, keywords, e.getMessage(), e);
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

            JsonNode paths = root.path("route").path("paths");
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
            log.error("[观察] 调用失败 plan_route(origin=\"{}\", destination=\"{}\"): {}, 建议: 检查网络连接",
                    origin, destination, e.getMessage(), e);
            return "{\"error\":\"路线规划请求异常\"}";
        }
    }
}
