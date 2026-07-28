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

    private static final int MAX_PLACE_LENGTH = 100;
    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";

    @Tool(name = "geocode", description = "根据地名查询经纬度坐标。返回坐标信息，不直接展示给用户")
    public String geocode(@ToolParam(description = "地名、地址或城市名称，例如：北京、上海、杭州西湖、天安门") String place) {
        if (place == null || place.isBlank()) {
            return "{\"error\":\"place 参数不能为空\"}";
        }
        if (place.length() > MAX_PLACE_LENGTH) {
            return "{\"error\":\"place 参数过长\"}";
        }

        try {
            log.info("执行地址解析工具: place={}", place);

            URI uri = UriComponentsBuilder.fromUriString(GEOCODE_URL)
                    .queryParam("key", amapApiKey)
                    .queryParam("address", place)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            String response = restTemplate.getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);

            String status = root.path("status").asText("0");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("未知错误");
                return "{\"error\":\"地址解析失败：" + info + "\"}";
            }

            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                return "{\"error\":\"未找到地点：" + place + "\"}";
            }

            JsonNode result = geocodes.get(0);
            String formattedAddress = result.path("formatted_address").asText(place);
            String location = result.path("location").asText("");
            String country = result.path("country").asText("");
            String province = result.path("province").asText("");
            String city = result.path("city").asText("");
            String district = result.path("district").asText("");

            String[] parts = location.split(",");
            if (parts.length != 2) {
                return "{\"error\":\"坐标格式异常：" + location + "\"}";
            }
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);

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
            return "{\"error\":\"地址解析异常\"}";
        }
    }
}
