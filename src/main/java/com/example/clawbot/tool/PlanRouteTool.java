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
 * 路线规划工具 — 驾车路线计算与导航步骤。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code plan_route}。
 * 调用<a href="https://lbs.amap.com/api/webservice/guide/api/direction/">高德地图路径规划 API</a>。</p>
 *
 * <h3>终点确定策略</h3>
 * <p>支持两种方式确定终点：</p>
 * <ol>
 *   <li><b>直接指定</b> — 用户提供明确的终点经纬度（destination 参数）</li>
 *   <li><b>周边搜索</b> — 用户提供关键词（如 "火锅"、"咖啡"），
 *       调用高德周边搜索 API 自动找到起点附近最近的匹配 POI 作为终点</li>
 * </ol>
 *
 * <h3>典型调用链</h3>
 * <pre>
 * 用户："从我当前位置开车到最近的海底捞怎么走？"
 *   → LLM 调用 geocode("当前位置") → 获取起点坐标
 *   → LLM 调用 plan_route(location="116.473,39.993", keywords="海底捞")
 *   → 自动搜索最近海底捞 → 规划驾车路线 → 返回距离、时长、步骤
 * </pre>
 *
 * <h3>返回数据</h3>
 * <p>返回 JSON 包含：总距离（米）、预计时间（秒）、分步导航指引数组。</p>
 *
 * @see com.example.clawbot.tool.GeocodeTool
 * @see com.example.clawbot.tool.SearchNearbyTool
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanRouteTool {

    /** HTTP 客户端 */
    private final RestTemplate restTemplate;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 高德地图 Web API 密钥 */
    @Value("${amap.api.key}")
    private String amapApiKey;

    /** 工具名称 */
    private static final String NAME = "plan_route";

    /**
     * 工具描述。
     *
     * <p>提示 LLM：起点由 location 参数指定（经纬度格式），
     * 若用户只提供地址则应先调用 geocode 工具转换。</p>
     */
    private static final String DESCRIPTION = "规划从起点到终点的路线。起点由 location 参数指定（经纬度格式），终点通过 keywords 或 types 搜索周边 POI 确定。若用户只提供地址，应先调用 geocode 工具转换为经纬度。";

    /** 高德驾车路径规划 API 端点 */
    private static final String AMAP_DIRECTION_URL = "https://restapi.amap.com/v3/direction/driving";

    /** 高德周边搜索 API 端点（用于自动发现终点） */
    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";

    /** 默认搜索半径（米），终点关键词搜索时使用 */
    private static final int DEFAULT_RADIUS = 3000;

    /** 最大搜索半径（米），防止无意义的大范围搜索 */
    private static final int MAX_RADIUS = 50000;

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "plan_route"
     */
    public String getToolName() {
        return NAME;
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>{@code location}（必填）— 起点经纬度 "经度,纬度"</li>
     *   <li>{@code destination}（可选）— 终点经纬度，提供则直接规划路线</li>
     *   <li>{@code keywords}（可选）— 终点搜索关键词，如 "火锅"、"咖啡"</li>
     *   <li>{@code types}（可选）— POI 类型编码，如 "050301"</li>
     *   <li>{@code radius}（可选）— 搜索半径，默认 3000 米，最大 50000 米</li>
     *   <li>{@code sortrule}（可选）— 排序规则：distance（距离优先）或 weight（综合评分优先）</li>
     * </ul>
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
     * <p>核心逻辑：确定终点 → 调用高德驾车路线规划 API → 返回格式化结果。</p>
     *
     * <p>参数校验规则：</p>
     * <ul>
     *   <li>location 必填且格式为 "经度,纬度"</li>
     *   <li>destination 可选，如果提供则格式必须为 "经度,纬度"</li>
     *   <li>如果不提供 destination，则 keywords 或 types 至少提供一个</li>
     *   <li>radius 范围为 1 ~ {@value #MAX_RADIUS} 米</li>
     *   <li>sortrule 仅允许 "distance" 或 "weight"</li>
     * </ul>
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

            // 1. 校验起点
            String origin = arguments.path("location").asText("").trim();
            if (origin.isEmpty()) {
                return "工具调用失败：location 参数不能为空";
            }
            if (!origin.matches("^-?\\d+\\.\\d+,-?\\d+\\.\\d+$")) {
                return "工具调用失败：location 格式不正确，应为 经度,纬度（如 116.473168,39.993015）";
            }

            String destination = arguments.path("destination").asText("").trim();

            // 2. 如果没有直接指定终点，通过周边搜索确定
            if (destination.isEmpty()) {
                String keywords = arguments.path("keywords").asText("").trim();
                String types = arguments.path("types").asText("").trim();

                if (keywords.isEmpty() && types.isEmpty()) {
                    return "工具调用失败：keywords、types 或 destination 参数至少需要提供一个";
                }

                // 解析并钳制半径参数
                int radius = DEFAULT_RADIUS;
                if (arguments.has("radius") && !arguments.path("radius").isNull()) {
                    radius = arguments.path("radius").asInt(DEFAULT_RADIUS);
                    if (radius < 1) radius = 1;
                    if (radius > MAX_RADIUS) radius = MAX_RADIUS;
                }

                // 解析并校验排序规则,如果没有指定排序方式，默认按照最近排序
                String sortrule = arguments.path("sortrule").asText("distance").trim();
                if (!"distance".equals(sortrule) && !"weight".equals(sortrule)) {
                    sortrule = "distance";
                }

                destination = searchDestination(origin, keywords, types, radius, sortrule);
                if (destination == null) {
                    return "{\"error\":\"未找到匹配的终点地点\"}";
                }
            } else {
                // 3. 校验直接指定的终点格式
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

    /**
     * 搜索终点坐标 — 调用高德周边搜索 API 找到起点附近最近的 POI。
     *
     * <p>使用 {@code offset=1} 仅取最近的一个结果，返回其坐标。
     * 如果 keywords 和 types 均提供，优先使用 keywords。</p>
     *
     * @param origin   起点经纬度
     * @param keywords 搜索关键词
     * @param types    POI 类型编码
     * @param radius   搜索半径（米）
     * @param sortrule 排序规则
     * @return 最近 POI 的 "经度,纬度" 字符串，未找到返回 null
     */
    private String searchDestination(String origin, String keywords, String types, int radius, String sortrule) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(AMAP_PLACE_AROUND_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("location", origin)
                    .queryParam("radius", radius)
                    .queryParam("sortrule", sortrule)
                    .queryParam("offset", 1)   // 只取最近的一个
                    .queryParam("page", 1)
                    .queryParam("extensions", "base")
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            // 追加搜索条件
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

    /**
     * 调用高德驾车路径规划 API 并返回结构化路线信息。
     *
     * <p>请求 {@code extensions=all} 获取详细的步骤指引（分步导航），
     * 而非仅总距离和总时间。</p>
     *
     * <h3>返回内容</h3>
     * <p>JSON 包含三个顶层字段：</p>
     * <ul>
     *   <li>{@code distance} — 总距离（米）</li>
     *   <li>{@code duration} — 预计耗时（秒）</li>
     *   <li>{@code steps} — 分步导航数组，每步包含：
     *     instruction（指引文字）、distance（该步距离）、
     *     duration（该步耗时）、action（动作类型）</li>
     * </ul>
     *高德返回的驾车路线 JSON 结构：
     *
     *
     * JSON
     *
     * {
     *   "status": "1",
     *   "route": {
     *     "paths": [
     *       {
     *         "distance": "5000",
     *         "duration": "600",
     *         "steps": [
     *           {
     *             "instruction": "沿长安街向东行驶500米",
     *             "distance": "500",
     *             "duration": "30",
     *             "action": "向前"
     *           },
     *           ...
     *         ]
     *       }
     *     ]
     *   }
     * }
     * @param origin      起点经纬度
     * @param destination 终点经纬度
     * @return 路线规划结果 JSON 字符串
     */
    private String planRoute(String origin, String destination) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(AMAP_DIRECTION_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .queryParam("extensions", "all")  // 获取详细导航步骤
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

            // 取第一条推荐路线
            JsonNode path = paths.get(0);
            String distance = path.path("distance").asText("");
            String duration = path.path("duration").asText("");

            // 提取分步导航指引
            List<Map<String, Object>> steps = new java.util.ArrayList<>();
            JsonNode stepsNode = path.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode step : stepsNode) {
                    Map<String, Object> stepItem = new java.util.LinkedHashMap<>();
                    stepItem.put("instruction", step.path("instruction").asText(""));//导航文字指引
                    stepItem.put("distance", step.path("distance").asText(""));//该段距离
                    stepItem.put("duration", step.path("duration").asText(""));//该段耗时
                    stepItem.put("action", step.path("action").asText(""));//动作类型，例如向左转
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
