package com.example.mission.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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

    public String getWeather(String city) {
        try {
            String url = buildWeatherUrl(city.trim());
            String response = restTemplate.getForObject(url, String.class);

            ObjectMapper mapper = new ObjectMapper();
            //利用json将字符串解析为数形结构
            JsonNode root = mapper.readTree(response);

            if (root.has("status_code")) {
                return "查询失败：" + root.path("status").asText("未知错误");
            }

            JsonNode result = root.get("results").get(0);//提取子节点
            String cityName = result.get("location").get("name").asText();//城市名
            String weather = result.get("now").get("text").asText();//天气描述
            String temp = result.get("now").get("temperature").asText();//温度
            String lastUpdate = result.get("last_update").asText();//时间

            return String.format("📍 %s 当前天气\n\n" +
                            "☁️ 天气：%s\n" +
                            "🌡 温度：%s°C\n" +
                            "⏰ 更新：%s",
                    cityName, weather, temp, lastUpdate.replace("T", " ").replace("+08:00", ""));

        } catch (Exception e) {
            log.error("查询天气失败, city: {}", city, e);
            return "抱歉，查询「" + city + "」天气失败了，请检查城市名是否正确。";
        }
    }
}
