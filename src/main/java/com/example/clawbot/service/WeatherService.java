package com.example.clawbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final RestTemplate restTemplate;

    @Value("${weather.api.key}")
    private String weatherApiKey;

    private String buildWeatherUrl(String city) {
        return "https://api.seniverse.com/v3/weather/now.json?key=" + weatherApiKey
                + "&location=" + city + "&language=zh-Hans&unit=c";
    }

    // Function Calling 专用：返回结构化天气数据
    public Map<String, Object> getWeatherData(String city) throws JsonProcessingException {
        String url = buildWeatherUrl(city.trim());
        String response = restTemplate.getForObject(url, String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response);

        if (root.has("status_code")) {
            return Map.of("error", "查询失败：" + root.path("status_code").asText());
        }

        JsonNode result = root.get("results").get(0);
        String cityName = result.get("location").get("name").asText();
        String weather = result.get("now").get("text").asText();
        String temp = result.get("now").get("temperature").asText();
        String humidity = result.get("now").get("humidity").asText();
        String windDir = result.get("now").get("wind_direction").asText();
        String windScale = result.get("now").get("wind_scale").asText();
        String lastUpdate = result.get("last_update").asText();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("city", cityName);
        data.put("weather", weather);
        data.put("temperature", temp + "°C");
        data.put("humidity", humidity + "%");
        data.put("wind_direction", windDir);
        data.put("wind_scale", windScale + "级");
        data.put("last_update", lastUpdate.replace("T", " ").replace("+08:00", ""));
        return data;
    }

    public String getWeather(String city) {
        try {
            Map<String, Object> data = getWeatherData(city);
            if (data.containsKey("error")) {
                return (String) data.get("error");
            }
            return String.format("📍 %s 当前天气\n\n" +
                            "☁️ 天气：%s\n" +
                            "🌡 温度：%s\n" +
                            "💧 湿度：%s\n" +
                            "🌬 风向：%s %s\n" +
                            "⏰ 更新：%s",
                    data.get("city"), data.get("weather"), data.get("temperature"),
                    data.get("humidity"), data.get("wind_direction"),
                    data.get("wind_scale"), data.get("last_update"));
        } catch (Exception e) {
            log.error("查询天气失败, city: {}", city, e);
            return "抱歉，查询「" + city + "」天气失败了，请检查城市名是否正确。";
        }
    }
}
