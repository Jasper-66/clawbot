package com.example.clawbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 天气查询服务 — 实时天气数据提供者。
 *
 * <p>调用<a href="https://www.seniverse.com/">心知天气</a>（seniverse.com）API
 * 查询指定城市的实时天气信息，包括天气状况、温度和更新时间。</p>
 *
 * <h3>API 说明</h3>
 * <p>使用心知天气 v3 免费版 API，端点：
 * {@code GET https://api.seniverse.com/v3/weather/now.json?key=xxx&location=北京&language=zh-Hans&unit=c}</p>
 *
 * <p>免费版限制：仅支持国内城市、QPS 较低、数据更新频率为小时级。</p>
 *
 * <h3>错误处理</h3>
 * <p>三层异常捕获，逐级细化：</p>
 * <ol>
 *   <li>{@link org.springframework.web.client.HttpClientErrorException} — HTTP 4xx 错误（如 API key 无效）</li>
 *   <li>通用 {@link Exception} — 网络超时、JSON 解析失败等</li>
 *   <li>所有异常均返回用户可读的错误提示，不向上层抛出</li>
 * </ol>
 *
 * <p>被 {@link com.example.clawbot.tool.WeatherTool} 调用，
 * 也可由其他业务代码直接注入使用。</p>
 *
 * @see com.example.clawbot.tool.WeatherTool
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    /** HTTP 客户端，用于 GET 请求心知天气 API */
    private final RestTemplate restTemplate;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 心知天气 API 密钥。
     *
     * <p>默认值为空字符串（防止 Spring 注入失败），
     * 生产环境应在 {@code application.properties} 中配置
     * {@code weather.api.key}。</p>
     */
    @Value("${weather.api.key:}")
    private String weatherApiKey;

    /**
     * 查询指定城市的实时天气。
     *
     * <p>API 未配置时返回友好提示，避免空指针异常。</p>
     *
     * <h3>返回示例</h3>
     * <pre>
     * 📍 北京 当前天气
     *
     * ☁️ 天气：晴
     * 🌡 温度：25°C
     * ⏰ 更新：2024-01-15 14:30:00
     * </pre>
     *
     * @param city 城市名称，如 "北京"、"上海"、"杭州"
     * @return 格式化的天气信息文本（含 emoji 图标），或错误提示
     */
    public String getWeather(String city) {
        // 前置检查：API Key 未配置时返回友好提示
        if (weatherApiKey == null || weatherApiKey.trim().isEmpty()) {
            log.error("天气 API Key 未配置，请在 application.properties 中设置 weather.api.key");
            return "抱歉，天气查询服务未配置 API Key。";
        }

        String trimmedCity = city.trim();
        String key = weatherApiKey.trim();

        // 构建心知天气 v3 now.json 请求 URL
        // 参数：key(API密钥)、location(城市)、language(zh-Hans中文简体)、unit(c摄氏度)
        String url = UriComponentsBuilder
                .fromHttpUrl("https://api.seniverse.com/v3/weather/now.json")
                .queryParam("key", key)
                .queryParam("location", trimmedCity)
                .queryParam("language", "zh-Hans")
                .queryParam("unit", "c")
                .build()
                .toUriString();

        // 日志中仅打印 API Key 前 4 位，避免泄露完整密钥
        log.info("调用心知天气: keyPrefix={}, city={}",
                key.length() > 4 ? key.substring(0, 4) + "***" : "***",
                trimmedCity);

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            // 心知天气通过 status_code 字段返回业务错误（非 HTTP 状态码）
            if (root.has("status_code")) {
                String statusCode = root.path("status_code").asText();
                String statusMsg = root.path("status").asText("未知错误");
                log.error("心知天气返回错误: status_code={}, status={}, city={}",
                        statusCode, statusMsg, trimmedCity);
                return "查询失败：" + statusMsg + "（错误码：" + statusCode + "）";
            }

            // 解析响应结构：results[0] → { location: {name}, now: {text, temperature}, last_update }
            JsonNode result = root.get("results").get(0);
            String cityName = result.get("location").get("name").asText();
            String weather = result.get("now").get("text").asText();
            String temp = result.get("now").get("temperature").asText();
            String lastUpdate = result.get("last_update").asText();

            // 格式化输出（替换 ISO 时间格式中的 T 和时区标记为可读格式）
            return String.format("📍 %s 当前天气\n\n" +
                            "☁️ 天气：%s\n" +
                            "🌡 温度：%s°C\n" +
                            "⏰ 更新：%s",
                    cityName, weather, temp,
                    lastUpdate.replace("T", " ").replace("+08:00", ""));

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // HTTP 4xx（如 403 API key 无效）
            log.error("心知天气 HTTP 错误: status={}, body={}, city={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), trimmedCity, e);
            return "查询失败：HTTP " + e.getStatusCode() + " - " + e.getResponseBodyAsString();
        } catch (Exception e) {
            // 其他异常：网络超时、JSON 解析失败等
            log.error("心知天气查询异常: city={}", trimmedCity, e);
            return "抱歉，查询「" + trimmedCity + "」天气失败：" + e.getMessage();
        }
    }
}
