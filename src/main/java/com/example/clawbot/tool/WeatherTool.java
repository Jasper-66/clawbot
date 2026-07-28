package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 天气查询工具：LLM 可调用查询指定城市的实时天气
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final WeatherService weatherService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "get_weather";
    private static final String DESCRIPTION = "查询指定城市的实时天气信息，包括天气状况、温度和更新时间。当用户询问某个城市的天气时使用此工具。";
    private static final int MAX_CITY_LENGTH = 50;

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "get_weather"
     */
    public String getToolName() {
        return NAME;
    }

    @Tool(name = "get_weather", description = "查询指定城市的实时天气信息，包括天气状况、温度和更新时间。当用户询问某个城市的天气时使用此工具。")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：北京、上海、深圳、杭州、成都、东京、纽约等") String city) {
        if (city == null || city.trim().isEmpty()) {
            return "工具调用失败：city 参数不能为空";
        }
        String trimmedCity = city.trim();
        if (trimmedCity.length() > MAX_CITY_LENGTH) {
            return "工具调用失败：city 参数过长";
        }

        log.info("[行动] LLM调用工具: get_weather(city=\"{}\") → 查询心知天气API获取实时天气数据", trimmedCity);
        String result = weatherService.getWeather(trimmedCity);
        log.info("[观察] 工具返回: get_weather → \"{}\" ({}字符)", result.length() > 60 ? result.substring(0, 60) + "..." : result, result.length());
        return result;
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * @return Function Calling 格式的工具定义 Map
     */
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

    /**
     * 校验并执行 LLM 请求的工具调用。
     *
     * @param functionName  LLM 返回的工具名称（应为 "get_weather"）
     * @param argumentsJson LLM 生成的参数 JSON 字符串（如 {@code {"city":"北京"}}）
     * @return 天气查询结果文本（含 emoji 格式），或错误说明
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }
        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String city = arguments.path("city").asText("").trim();
            return getWeather(city);
        } catch (Exception e) {
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
