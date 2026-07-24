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

/** LLM Function Calling 工具，调用高德地理编码 API 将地名/地址转为经纬度坐标。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeocodeTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${amap.api.key}")
    private String amapApiKey;

    private static final String NAME = "geocode";
    private static final int MAX_PLACE_LENGTH = 100;
    private static final String DESCRIPTION = "根据地名查询经纬度坐标。当用户提到某个地点、地址或城市，且后续需要基于经纬度调用其他工具（如天气、地图等）时使用此工具。返回坐标信息，不直接展示给用户。";
    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";

    public String getToolName() {
        return NAME;
    }

    /** 返回 OpenAI Function Calling 格式的工具定义。 */
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

    /** 校验参数并调用高德地理编码 API，返回地址的经纬度坐标 JSON。 */
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
            }
            if (place.length() > MAX_PLACE_LENGTH) {
                return "工具调用失败：place 参数过长";
            }

            log.info("执行地址解析工具: place={}", place);

            // 调用高德地理编码 API（需 UTF-8 编码中文参数）
            URI uri = UriComponentsBuilder.fromUriString(GEOCODE_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("address", place)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            String response = restTemplate.getForObject(uri, String.class);
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
            String formattedAddress = result.path("formatted_address").asText(place);
            String location = result.path("location").asText("");
            String country = result.path("country").asText("");
            String province = result.path("province").asText("");
            String city = result.path("city").asText("");
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
