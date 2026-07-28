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
import java.util.List;
import java.util.Map;

// 地址解析工具：LLM 可调用将中文地址转换为经纬度坐标（高德地图 API）
@Slf4j
@Component
@RequiredArgsConstructor
public class GeocodeTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${amap.api.key}")
    private String amapApiKey;

    private static final String NAME = "geocode";
    private static final String DESCRIPTION = "根据地名查询经纬度坐标。当用户提到某个地点、地址或城市，且后续需要基于经纬度调用其他工具（如天气、地图等）时使用此工具。返回坐标信息，不直接展示给用户。";
    private static final int MAX_PLACE_LENGTH = 100;
    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "geocode"
     */
    public String getToolName() {
        return NAME;
    }

    @Tool(name = "geocode", description = "根据地名查询经纬度坐标。当用户提到某个地点、地址或城市，且后续需要基于经纬度调用其他工具（如天气、地图等）时使用此工具。返回坐标信息，不直接展示给用户。")
    public String geocode(
            @ToolParam(description = "地名、地址或城市名称，例如：北京、上海、杭州西湖、天安门、东京、纽约等") String place) {
        if (place == null || place.trim().isEmpty()) {
            return "工具调用失败：place 参数不能为空";
        }
        String trimmedPlace = place.trim();
        if (trimmedPlace.length() > MAX_PLACE_LENGTH) {
            return "工具调用失败：place 参数过长";
        }

        log.info("[行动] LLM调用工具: geocode(place=\"{}\") → 调用高德地图API将地址转为经纬度坐标", trimmedPlace);

        try {
            URI uri = UriComponentsBuilder.fromUriString(GEOCODE_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("address", trimmedPlace)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);

            String status = root.path("status").asText("0");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("未知错误");
                return String.format("{\"error\":\"地址解析失败：%s\"}", info);
            }

            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                return String.format("{\"error\":\"未找到地点：%s\"}", trimmedPlace);
            }
            JsonNode result = geocodes.get(0);

            String formattedAddress = result.path("formatted_address").asText(trimmedPlace);
            String location = result.path("location").asText("");
            String country = result.path("country").asText("");
            String province = result.path("province").asText("");
            String city = result.path("city").asText("");
            String district = result.path("district").asText("");

            String[] parts = location.split(",");
            if (parts.length != 2) {
                return String.format("{\"error\":\"坐标格式异常：%s\"}", location);
            }
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);

            log.info("[观察] 工具返回: geocode → \"{}\" → 坐标({}, {}), {}{}{}{}",
                    trimmedPlace, longitude, latitude,
                    country, province, city, district);
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
            log.error("[异常] 工具调用失败 geocode(place=\"{}\") | 原因: {} | 建议: 检查高德地图 API 密钥",
                    trimmedPlace, e.getMessage(), e);
            return "工具调用失败：地址解析异常: " + e.getMessage();
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
     * @param functionName  工具名称（应为 "geocode"）
     * @param argumentsJson LLM 生成的参数 JSON（如 {@code {"place":"北京"}}）
     * @return 坐标信息 JSON 字符串（供 LLM 阅读），或错误信息
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }
        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String place = arguments.path("place").asText("").trim();
            return geocode(place);
        } catch (Exception e) {
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
