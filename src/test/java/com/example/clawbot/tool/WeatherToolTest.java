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
    void shouldReturnWeatherForValidCity() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州：晴，28°C");

        String result = weatherTool.getWeather("杭州");

        assertThat(result).isEqualTo("杭州：晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    @Test
    void shouldTrimCityName() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州：晴，28°C");

        String result = weatherTool.getWeather("  杭州 ");

        assertThat(result).isEqualTo("杭州：晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    @Test
    void shouldRejectEmptyCity() {
        String result = weatherTool.getWeather("");
        assertThat(result).contains("city 参数不能为空");
    }

    @Test
    void shouldRejectNullCity() {
        String result = weatherTool.getWeather(null);
        assertThat(result).contains("city 参数不能为空");
    }
}
