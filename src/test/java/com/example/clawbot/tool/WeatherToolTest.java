package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherToolTest {

    private final WeatherService weatherService = mock(WeatherService.class);
    private final WeatherTool weatherTool = new WeatherTool(weatherService);

    @Test
    void shouldExposeFunctionDefinition() {
        Map<String, Object> definition = weatherTool.getToolDefinition();

        assertThat(definition.get("type")).isEqualTo("function");

        assertThat(definition.toString()).contains("get_weather", "city");
    }

    @Test
    void shouldExecuteWeatherQueryWithValidatedCity() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州：晴，28°C");


        String result = weatherTool.execute("get_weather", "{\"city\":\" 杭州 \"}");


        assertThat(result).isEqualTo("杭州：晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    @Test
    void shouldRejectInvalidArguments() {

        assertThat(weatherTool.execute(weatherTool.getToolName(), "{}"))
                .contains("city 参数不能为空");
        assertThat(weatherTool.execute(weatherTool.getToolName(), "not-json"))
                .contains("不是有效的 JSON");
        assertThat(weatherTool.execute("unknown_tool", "{}"))
                .contains("不支持的工具");
    }
}
