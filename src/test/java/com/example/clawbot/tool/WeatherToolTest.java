package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherToolTest {

    private final WeatherService weatherService = mock(WeatherService.class);
    private final WeatherTool weatherTool = new WeatherTool(weatherService);

    @Test
    void shouldExecuteWeatherQueryWithValidCity() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州：晴，28°C");

        String result = weatherTool.getWeather("杭州");

        assertThat(result).isEqualTo("杭州：晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    @Test
    void shouldTrimCityAndExecute() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州：晴，28°C");

        String result = weatherTool.getWeather(" 杭州 ");

        assertThat(result).isEqualTo("杭州：晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    @Test
    void shouldRejectEmptyCity() {
        assertThat(weatherTool.getWeather(""))
                .contains("city 参数不能为空");
        assertThat(weatherTool.getWeather(null))
                .contains("city 参数不能为空");
    }

    @Test
    void shouldRejectOverlyLongCity() {
        String longCity = "A".repeat(51);
        assertThat(weatherTool.getWeather(longCity))
                .contains("city 参数过长");
    }
}
