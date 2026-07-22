package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherToolTest {

    private final WeatherService weatherService = mock(WeatherService.class);
    private final WeatherTool weatherTool = new WeatherTool(weatherService, new ObjectMapper());

    @Test
    void shouldExposeFunctionDefinition() {
        Map<String, Object> definition = weatherTool.definition();

        assertThat(definition.get("type")).isEqualTo("function");
        assertThat(definition.toString()).contains(WeatherTool.NAME, "city");
    }

    @Test
    void shouldExecuteWeatherQueryWithValidatedCity() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州：晴，28°C");

        String result = weatherTool.execute(WeatherTool.NAME, "{\"city\":\" 杭州 \"}");

        assertThat(result).isEqualTo("杭州：晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThat(weatherTool.execute(WeatherTool.NAME, "{}"))
                .contains("city 参数不能为空");
        assertThat(weatherTool.execute(WeatherTool.NAME, "not-json"))
                .contains("不是有效的 JSON");
        assertThat(weatherTool.execute("unknown_tool", "{}"))
                .contains("不支持的工具");
    }
}
