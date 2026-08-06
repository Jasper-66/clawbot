package com.example.clawbot.config;

import com.example.clawbot.liepin.tool.LiepinJobTool;
import com.example.clawbot.tool.GeocodeTool;
import com.example.clawbot.tool.PlanRouteTool;
import com.example.clawbot.tool.SearchNearbyTool;
import com.example.clawbot.tool.SearchTool;
import com.example.clawbot.tool.TextToSpeechTool;
import com.example.clawbot.tool.WeatherTool;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将 {@code @Tool} 注解的工具方法通过 {@link ToolCallbacks#from} 包装为
 * {@link FunctionCallback} Bean，供 Spring AI function calling 使用。
 */
@Configuration
public class ToolFunctionConfig {

    @Bean
    FunctionCallback weatherCallback(WeatherTool tool) {
        return ToolCallbacks.from(tool)[0];
    }

    @Bean
    FunctionCallback geocodeCallback(GeocodeTool tool) {
        return ToolCallbacks.from(tool)[0];
    }

    @Bean
    FunctionCallback searchNearbyCallback(SearchNearbyTool tool) {
        return ToolCallbacks.from(tool)[0];
    }

    @Bean
    FunctionCallback planRouteCallback(PlanRouteTool tool) {
        return ToolCallbacks.from(tool)[0];
    }

    @Bean
    FunctionCallback searchCallback(SearchTool tool) {
        return ToolCallbacks.from(tool)[0];
    }

    @Bean
    FunctionCallback textToSpeechCallback(TextToSpeechTool tool) {
        return ToolCallbacks.from(tool)[0];
    }

    @Bean
    FunctionCallback liepinJobCallback(LiepinJobTool tool) {
        return ToolCallbacks.from(tool)[0];
    }
}
