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
<<<<<<< HEAD
        assertThat(definition.toString()).contains("get_weather", "city");
=======
        assertThat(definition.toString()).contains(weatherTool.getToolName(), "city");
>>>>>>> f5994d030b0e3eb78941bf38030cc3b2615bb91a
    }

    @Test
    void shouldExecuteWeatherQueryWithValidatedCity() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州：晴，28°C");

<<<<<<< HEAD
        String result = weatherTool.execute("get_weather", "{\"city\":\" 杭州 \"}");
=======
        String result = weatherTool.execute(weatherTool.getToolName(), "{\"city\":\" 杭州 \"}");
>>>>>>> f5994d030b0e3eb78941bf38030cc3b2615bb91a

        assertThat(result).isEqualTo("杭州：晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    @Test
    void shouldRejectInvalidArguments() {
<<<<<<< HEAD
        assertThat(weatherTool.execute("get_weather", "{}"))
                .contains("city 参数不能为空");
        assertThat(weatherTool.execute("get_weather", "not-json"))
=======
        assertThat(weatherTool.execute(weatherTool.getToolName(), "{}"))
                .contains("city 参数不能为空");
        assertThat(weatherTool.execute(weatherTool.getToolName(), "not-json"))
>>>>>>> f5994d030b0e3eb78941bf38030cc3b2615bb91a
                .contains("不是有效的 JSON");
        assertThat(weatherTool.execute("unknown_tool", "{}"))
                .contains("不支持的工具");
    }
}
