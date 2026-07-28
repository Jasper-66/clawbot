package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

// 天气查询工具：LLM 可调用查询指定城市的实时天气
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final WeatherService weatherService;

    private static final int MAX_CITY_LENGTH = 50;

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
}
