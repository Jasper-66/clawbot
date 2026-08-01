package com.example.clawbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 调用心知天气 API 获取城市实时天气。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${weather.api.key:}")
    private String weatherApiKey;

    public String getWeather(String city) {
        if (weatherApiKey == null || weatherApiKey.trim().isEmpty()) {
            log.error("天气 API Key 未配置，请在 application.properties 中设置 weather.api.key");
            return "抱歉，天气查询服务未配置 API Key。";
        }

        String trimmedCity = city.trim();
        String key = weatherApiKey.trim();

        String url = UriComponentsBuilder
                .fromUriString("https://api.seniverse.com/v3/weather/now.json")
                .queryParam("key", key)
                .queryParam("location", trimmedCity)
                .queryParam("language", "zh-Hans")
                .queryParam("unit", "c")
                .build()
                .toUriString();

        log.info("调用心知天气: keyPrefix={}, city={}",
                key.length() > 4 ? key.substring(0, 4) + "***" : "***",
                trimmedCity);

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.has("status_code")) {
                String statusCode = root.path("status_code").asText();
                String statusMsg = root.path("status").asText("未知错误");
                log.error("心知天气返回错误: status_code={}, status={}, city={}",
                        statusCode, statusMsg, trimmedCity);
                return "查询失败：" + statusMsg + "（错误码：" + statusCode + "）";
            }

            JsonNode result = root.get("results").get(0);
            String cityName = result.get("location").get("name").asText();
            String weather = result.get("now").get("text").asText();
            String temp = result.get("now").get("temperature").asText();
            String lastUpdate = result.get("last_update").asText();

            return String.format("📍 %s 当前天气\n\n" +
                            "☁️ 天气：%s\n" +
                            "🌡 温度：%s°C\n" +
                            "⏰ 更新：%s",
                    cityName, weather, temp,
                    lastUpdate.replace("T", " ").replace("+08:00", ""));

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("心知天气 HTTP 错误: status={}, body={}, city={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), trimmedCity, e);
            return "查询失败：HTTP " + e.getStatusCode() + " - " + e.getResponseBodyAsString();
        } catch (Exception e) {
            log.error("心知天气查询异常: city={}", trimmedCity, e);
            return "抱歉，查询「" + trimmedCity + "」天气失败：" + e.getMessage();
        }
    }
}
