package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证典型调用链路：geocode → search_nearby
 * 场景：用户说"帮我找找天安门附近有什么火锅店"
 */
class GeocodeToSearchNearbyLinkTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldFindHotPotNearTiananmen() throws Exception {
        // ========== 步骤 1：geocode 解析地址 ==========
        GeocodeTool geocodeTool = new GeocodeTool(restTemplate);
        ReflectionTestUtils.setField(geocodeTool, "amapApiKey", "92e400c1e798f45771fb47106f38c1b1");
        String geocodeResult = geocodeTool.geocode("天安门");

        System.out.println("=== geocode 返回 ===");
        System.out.println(geocodeResult);

        assertThat(geocodeResult)
                .as("geocode 应返回有效坐标 JSON")
                .doesNotContain("error")
                .doesNotContain("失败");

        JsonNode geocodeJson = objectMapper.readTree(geocodeResult);
        double longitude = geocodeJson.path("longitude").asDouble();
        double latitude = geocodeJson.path("latitude").asDouble();
        assertThat(longitude).as("经度应大于 0").isGreaterThan(0);
        assertThat(latitude).as("纬度应大于 0").isGreaterThan(0);

        // 高德地图周边搜索 API 要求格式：经度,纬度
        String location = longitude + "," + latitude;
        System.out.println("转换后的 location: " + location);

        // ========== 步骤 2：search_nearby 搜索周边 ==========
        SearchNearbyTool searchNearbyTool = new SearchNearbyTool(restTemplate);
        ReflectionTestUtils.setField(searchNearbyTool, "amapApiKey", "92e400c1e798f45771fb47106f38c1b1");

        String searchResult = searchNearbyTool.searchNearby(location, "火锅", null, null, null);

        System.out.println("=== search_nearby 返回 ===");
        System.out.println(searchResult);

        assertThat(searchResult)
                .as("search_nearby 应返回有效结果")
                .doesNotContain("error")
                .doesNotContain("失败");

        JsonNode searchJson = objectMapper.readTree(searchResult);
        int count = searchJson.path("count").asInt(0);
        System.out.println("找到火锅店数量: " + count);

        assertThat(count).as("应至少找到 1 家火锅店").isGreaterThan(0);

        // 打印前 3 条结果供人工查看
        JsonNode results = searchJson.path("results");
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            JsonNode poi = results.get(i);
            System.out.printf("  [%d] %s | %s | 距离: %s米%n",
                    i + 1,
                    poi.path("name").asText(""),
                    poi.path("address").asText(""),
                    poi.path("distance").asText(""));
        }
    }
}
