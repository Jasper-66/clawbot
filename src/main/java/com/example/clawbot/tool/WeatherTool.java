package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** LLM Function Calling 工具，委托 WeatherService 查询指定城市的实时天气。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final WeatherService weatherService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "get_weather";
    private static final int MAX_CITY_LENGTH = 50;
    private static final String DESCRIPTION = "查询指定城市的实时天气信息，包括天气状况、温度和更新时间。当用户询问某个城市的天气时使用此工具。";

    public String getToolName() {
        return NAME;
    }

    /** 返回 OpenAI Function Calling 格式的工具定义。 */
    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", DESCRIPTION,
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

    /** 校验参数并委托 WeatherService 查询天气。 */
    public String execute(String functionName, String argumentsJson) {
        // 路由校验
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            // 解析参数 JSON
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String city = arguments.path("city").asText("").trim();

            // 参数校验：非空
            if (city.isEmpty()) {
                return "工具调用失败：city 参数不能为空";
            }

            // 参数校验：长度限制
            if (city.length() > MAX_CITY_LENGTH) {
                return "工具调用失败：city 参数过长";
            }

            log.info("执行天气工具: city={}", city);
            // 委托 WeatherService 执行真实查询
            return weatherService.getWeather(city);
        } catch (Exception e) {
            log.error("天气工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
