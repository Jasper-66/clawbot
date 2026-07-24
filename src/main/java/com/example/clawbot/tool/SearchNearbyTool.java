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
 * 周边搜索工具 — 搜索指定位置附近的 POI（兴趣点）。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code search_nearby}。
 * 调用<a href="https://lbs.amap.com/api/webservice/guide/api/newpoisearch/">高德地图周边搜索 API</a>。</p>
 *
 * <h3>使用场景</h3>
 * <p>当用户询问附近有什么时，LLM 调用此工具：</p>
 * <pre>
 * 用户："附近有什么好吃的火锅？"
 *   → LLM 先调用 geocode 获取用户位置坐标
 *   → LLM 调用 search_nearby(location="116.473,39.993", keywords="火锅")
 *   → 返回附近火锅店列表（名称、地址、距离、电话）
 * </pre>
 *
 * <h3>返回数据</h3>
 * <p>返回 JSON 包含：结果数量 + 每个 POI 的名称、类型、地址、坐标、电话、距离。
 * 最多返回 {@value #MAX_RESULTS} 条结果。</p>
 *
 * @see com.example.clawbot.tool.GeocodeTool
 * @see com.example.clawbot.tool.PlanRouteTool
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchNearbyTool {

    /** HTTP 客户端 */
    private final RestTemplate restTemplate;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 高德地图 Web API 密钥 */
    @Value("${amap.api.key}")
    private String amapApiKey;

    /** 工具名称 */
    private static final String NAME = "search_nearby";

    /**
     * 工具描述。
     *
     * <p>提示 LLM：location 必须为经纬度格式，若用户提供地址名应先调用 geocode 工具。</p>
     */
    private static final String DESCRIPTION = "搜索指定位置周边的 POI（兴趣点），如餐厅、商店、景点等。location 参数必须为 经度,纬度 格式，若用户提供的是地址名称，应先调用 geocode 工具获取坐标。";

    /** 高德周边搜索 API 端点 */
    private static final String AMAP_PLACE_AROUND_URL = "https://restapi.amap.com/v3/place/around";

    /** 默认搜索半径（米） */
    private static final int DEFAULT_RADIUS = 3000;

    /** 最大搜索半径（米） */
    private static final int MAX_RADIUS = 50000;

    /** 单次最多返回的 POI 数量，防止上下文超限 */
    private static final int MAX_RESULTS = 10;

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "search_nearby"
     */
    public String getToolName() {
        return NAME;
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>{@code location}（必填）— 中心点经纬度 "经度,纬度"，如 "116.473168,39.993015"</li>
     *   <li>{@code keywords}（可选）— 搜索关键词，如 "火锅"、"咖啡"、"游乐园"</li>
     *   <li>{@code types}（可选）— POI 类型编码，多个用逗号分隔</li>
     *   <li>{@code radius}（可选）— 搜索半径（米），默认 3000，最大 50000</li>
     *   <li>{@code sortrule}（可选）— distance（距离优先）或 weight（综合优先）</li>
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
     * <p>参数校验后调用高德周边搜索 API，将结果裁剪到
     * 最多 {@value #MAX_RESULTS} 条后以 JSON 返回。</p>
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
            // 解析并校验参数
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

            // 解析并钳制半径
            int radius = DEFAULT_RADIUS;
            if (arguments.has("radius") && !arguments.path("radius").isNull()) {
                radius = arguments.path("radius").asInt(DEFAULT_RADIUS);
                if (radius < 1) radius = 1;
                if (radius > MAX_RADIUS) radius = MAX_RADIUS;
            }

            // 解析并校验排序规则
            String sortrule = arguments.path("sortrule").asText("distance").trim();
            if (!"distance".equals(sortrule) && !"weight".equals(sortrule)) {
                sortrule = "distance";
            }

            log.info("执行周边搜索: location={}, keywords={}, types={}, radius={}, sortrule={}",
                    location, keywords, types, radius, sortrule);

            URI uri = buildUri(location, keywords, types, radius, sortrule);
            //发送Get请求
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

            // 提取 POI 结果（限制最大条数）
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

    /**
     * 构建高德周边搜索 API 请求 URI。
     *
     * <p>公共参数固定设置：offset（每页条数）、page（页码）、extensions（返回模式）。
     * keywords 和 types 可选，至少提供一个。</p>
     *
     * @param location 中心点 "经度,纬度"
     * @param keywords 搜索关键词（可为空）
     * @param types    POI 类型编码（可为空）
     * @param radius   搜索半径（米）
     * @param sortrule 排序规则（distance / weight）
     * @return 完整的编码后请求 URI
     */
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
