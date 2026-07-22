package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final WeatherService weatherService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOOL_NAME = "get_weather";
    private static final String TOOL_DESCRIPTION = "查询指定城市的实时天气信息，包括天气状况、温度和更新时间。当用户询问某个城市的天气时使用此工具。";

    public String getToolName() {
        return TOOL_NAME;
    }

    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", TOOL_NAME,
                        "description", TOOL_DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "city", Map.of(
                                                "type", "string",
                                                "description", "城市名称，例如：北京、上海、深圳、杭州、成都、东京、纽约等"
                                        )
                                ),
                                "required", List.of("city")
                        )
                )
        );
    }

    public String execute(String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            String city = args.get("city").asText();
            log.info("执行天气工具: city={}", city);
            return weatherService.getWeather(city);
        } catch (Exception e) {
            log.error("天气工具执行失败: {}", e.getMessage());
            return "天气查询失败：" + e.getMessage();
        }
    }
}
