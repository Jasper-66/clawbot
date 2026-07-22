package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 大模型负责选择工具和生成参数，WeatherService 负责执行真实查询。
 */
@Component
@RequiredArgsConstructor
public class WeatherTool {

    public static final String NAME = "get_current_weather";
    private static final int MAX_CITY_LENGTH = 64;

    private final WeatherService weatherService;
    private final ObjectMapper objectMapper;

    /**
     * 返回 OpenAI 兼容的 Function Calling 工具定义
     */
    public Map<String, Object> definition() {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of(
                                "type", "string",
                                "description", "要查询的城市名称，例如：杭州、北京市、上海"
                        )
                ),
                "required", List.of("city"),
                "additionalProperties", false
        );

        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", "查询指定城市的实时天气、气温和数据更新时间。用户没有提供城市时，应先询问城市。",
                        "parameters", parameters
                )
        );
    }

    /**
     * 校验并执行模型返回的工具调用。
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String city = arguments.path("city").asText("").trim();
            if (city.isEmpty()) {
                return "工具调用失败：city 参数不能为空";
            }
            if (city.length() > MAX_CITY_LENGTH) {
                return "工具调用失败：city 参数过长";
            }
            return weatherService.getWeather(city);
        } catch (Exception e) {
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
