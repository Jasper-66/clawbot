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
 * 地理编码工具 — 地名/地址转经纬度坐标。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code geocode}。
 * 调用<a href="https://lbs.amap.com/api/webservice/guide/api/georegeo/">高德地图地理编码 API</a>，
 * 对中文地址有较好的解析能力。</p>
 *
 * <h3>使用场景</h3>
 * <p>当用户提到某个地点但后续操作需要经纬度时，LLM 会先调用此工具获取坐标。
 * 典型调用链：</p>
 * <pre>
 * 用户："北京故宫附近有什么咖啡馆？"
 *   → LLM 调用 geocode("北京故宫") → 获取经纬度
 *   → LLM 调用 search_nearby(location="116.397,39.916", keywords="咖啡")
 *   → 返回周边咖啡馆列表
 * </pre>
 *
 * <h3>返回数据</h3>
 * <p>工具执行结果是以 JSON 字符串返回给 LLM 的（不直接展示给用户），包含：</p>
 * <ul>
 *   <li>{@code formatted_address} — 标准化地址</li>
 *   <li>{@code location} — "经度,纬度" 格式字符串</li>
 *   <li>{@code longitude/latitude} — 拆分的数值</li>
 *   <li>{@code country/province/city/district} — 行政区划</li>
 * </ul>
 *
 * @see com.example.clawbot.tool.SearchNearbyTool
 * @see com.example.clawbot.tool.PlanRouteTool
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeocodeTool {

    /** HTTP 客户端，用于调用高德地图 API */
    private final RestTemplate restTemplate;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 高德地图 Web API 密钥 */
    @Value("${amap.api.key}")
    private String amapApiKey;

    /** 工具名称，对应 LLM Function Calling 的 function.name */
    private static final String NAME = "geocode";

    /** 地点参数最大长度，防止恶意超长输入 */
    private static final int MAX_PLACE_LENGTH = 100;

    /** 工具描述，供 LLM 理解何时应使用此工具 */
    private static final String DESCRIPTION = "根据地名查询经纬度坐标。当用户提到某个地点、地址或城市，且后续需要基于经纬度调用其他工具（如天气、地图等）时使用此工具。返回坐标信息，不直接展示给用户。";

    /** 高德地理编码 API 端点 */
    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";

    /**
     * 获取工具名称。
     *
     * <p>用于在 {@link com.example.clawbot.service.LlmService
     * 以及与其他 Tool 区分。</p>
     *
     * @return 工具标识名 "geocode"
     */
    public String getToolName() {
        return NAME;
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * <p>向 LLM 描述此工具的名称、用途和参数 JSON Schema。
     * LLM 根据此定义判断何时调用以及如何生成参数。</p>
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>{@code place}（必填）— 地名、地址或城市名称，如 北京、上海、杭州西湖、天安门</li>
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
                                        "place", Map.of(
                                                "type", "string",
                                                "description", "地名、地址或城市名称，例如：北京、上海、杭州西湖、天安门、东京、纽约等"
                                        )
                                ),
                                "required", List.of("place")
                        )
                )
        );
    }

    /**
     * 校验并执行 LLM 请求的工具调用。
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>校验 functionName 是否匹配</li>
     *   <li>解析参数 JSON，提取 place 字段</li>
     *   <li>参数校验：非空、长度限制</li>
     *   <li>调用高德地理编码 API</li>
     *   <li>解析 location（"经度,纬度"格式）并拆分为 double 值</li>
     *   <li>组装包含坐标和行政区划的 JSON 返回给 LLM</li>
     * </ol>
     *
     * <p>高德 API 的 location 格式为 "经度,纬度"（注意顺序），
     * 与 Google Maps 的 "纬度,经度" 相反。</p>
     *
     * @param functionName  工具名称（应为 "geocode"）
     * @param argumentsJson LLM 生成的参数 JSON（如 {@code {"place":"北京"}}）
     * @return 坐标信息 JSON 字符串（供 LLM 阅读），或错误信息
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            // 解析并校验参数
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String place = arguments.path("place").asText("").trim();
            if (place.isEmpty()) {
                return "工具调用失败：place 参数不能为空";
            }//长度检验
            if (place.length() > MAX_PLACE_LENGTH) {
                return "工具调用失败：place 参数过长";
            }

            log.info("执行地址解析工具: place={}", place);

            // 调用高德地理编码 API（需 UTF-8 编码中文参数）
            //构建URL请求
            URI uri = UriComponentsBuilder.fromUriString(GEOCODE_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("address", place)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

             //发Get请求
            String response = restTemplate.getForObject(uri, String.class);
            //解析成JsonNode
            JsonNode root = objectMapper.readTree(response);

            // 高德 API 用 status="1" 表示成功
            String status = root.path("status").asText("0");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("未知错误");
                return String.format("{\"error\":\"地址解析失败：%s\"}", info);
            }

            // geocodes 数组包含匹配结果，取第一个（最佳匹配）
            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                return String.format("{\"error\":\"未找到地点：%s\"}", place);
            }
            JsonNode result = geocodes.get(0);

            //标准化地址
            String formattedAddress = result.path("formatted_address").asText(place);
            //经度纬度
            String location = result.path("location").asText("");
            String country = result.path("country").asText("");
            String province = result.path("province").asText("");
            String city = result.path("city").asText("");
            //区县
            String district = result.path("district").asText("");

            // 高德 location 格式："经度,纬度" → 拆分为两个 double
            String[] parts = location.split(",");
            if (parts.length != 2) {
                return String.format("{\"error\":\"坐标格式异常：%s\"}", location);
            }
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);

            // 返回 JSON 格式结果，包含丰富的结构化信息供 LLM 使用
            return objectMapper.writeValueAsString(Map.of(
                    "name", formattedAddress,
                    "location", location,
                    "longitude", longitude,
                    "latitude", latitude,
                    "country", country,
                    "province", province,
                    "city", city,
                    "district", district
            ));
        } catch (Exception e) {
            log.error("地址解析工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
