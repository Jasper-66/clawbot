package com.example.clawbot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;

/**
 * 手动验证调用链路：geocode → search_nearby
 * 场景：用户说"帮我找找天安门附近有什么火锅店"
 */
public class LinkTestMain {

    public static void main(String[] args) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        ObjectMapper objectMapper = new ObjectMapper();

        // ========== 步骤 1：geocode 解析地址 ==========
        System.out.println("【步骤 1】调用 geocode(place=\"天安门\")...");
        GeocodeTool geocodeTool = new GeocodeTool(restTemplate);
        String geocodeResult = geocodeTool.execute("geocode", "{\"place\":\"天安门\"}");

        System.out.println("geocode 返回:");
        System.out.println(geocodeResult);
        System.out.println();

        if (geocodeResult.contains("error") || geocodeResult.contains("失败")) {
            System.err.println("geocode 失败，链路中断");
            return;
        }

        JsonNode geocodeJson = objectMapper.readTree(geocodeResult);
        double longitude = geocodeJson.path("longitude").asDouble();
        double latitude = geocodeJson.path("latitude").asDouble();
        String location = longitude + "," + latitude;

        System.out.println("→ 提取坐标: longitude=" + longitude + ", latitude=" + latitude);
        System.out.println("→ 高德格式 location: " + location);
        System.out.println();

        // ========== 步骤 2：search_nearby 搜索周边 ==========
        System.out.println("【步骤 2】调用 search_nearby(location=\"" + location + "\", keywords=\"火锅\")...");
        SearchNearbyTool searchNearbyTool = new SearchNearbyTool(restTemplate);
        // 注入高德 Key（实际运行前请确保 amap.api.key 已配置）
        String amapKey = System.getProperty("amap.api.key", "92e400c1e798f45771fb47106f38c1b1");
        org.springframework.test.util.ReflectionTestUtils.setField(
                searchNearbyTool, "amapApiKey", amapKey);

        String searchResult = searchNearbyTool.execute("search_nearby",
                "{\"location\":\"" + location + "\",\"keywords\":\"火锅\"}");

        System.out.println("search_nearby 返回:");
        System.out.println(searchResult);
        System.out.println();

        if (searchResult.contains("error") || searchResult.contains("失败")) {
            System.err.println("search_nearby 失败，链路中断");
            return;
        }

        JsonNode searchJson = objectMapper.readTree(searchResult);
        int count = searchJson.path("count").asInt(0);
        System.out.println("→ 找到火锅店数量: " + count);

        JsonNode results = searchJson.path("results");
        System.out.println("→ 前 3 条结果:");
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            JsonNode poi = results.get(i);
            System.out.printf("   [%d] %s | 地址: %s | 距离: %s米%n",
                    i + 1,
                    poi.path("name").asText(""),
                    poi.path("address").asText(""),
                    poi.path("distance").asText(""));
        }

        System.out.println();
        System.out.println("✅ 调用链路验证通过！");
    }
}
