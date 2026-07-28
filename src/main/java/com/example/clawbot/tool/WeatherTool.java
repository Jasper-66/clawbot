package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final WeatherService weatherService;

    private static final int MAX_CITY_LENGTH = 50;

    @Tool(name = "get_weather", description = "查询指定城市的实时天气信息，包括天气状况、温度和更新时间")
    public String getWeather(@ToolParam(description = "城市名称，例如：北京、上海、深圳、杭州、成都、东京、纽约") String city) {
        if (city == null || city.isBlank()) {
            return "工具调用失败：city 参数不能为空";
        }
        if (city.length() > MAX_CITY_LENGTH) {
            return "工具调用失败：city 参数过长";
        }
        log.info("执行天气工具: city={}", city);
        return weatherService.getWeather(city.trim());
    }
}
