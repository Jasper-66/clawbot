package com.example.clawbot.service;

import com.example.clawbot.tool.WeatherTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmServiceFunctionCallingTest {

    private static final String CHAT_URL = "https://api.deepseek.com/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WeatherService weatherService = mock(WeatherService.class);
    private final WeatherTool weatherTool = new WeatherTool(weatherService, objectMapper);
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private LlmService llmService;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        llmService = new LlmService(restTemplate, weatherTool);
        ReflectionTestUtils.setField(llmService, "apiKey", "test-key");
        ReflectionTestUtils.setField(llmService, "baseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(llmService, "model", "deepseek-chat");
    }

    @Test
    void shouldExecuteWeatherToolAndSendResultBackToModel() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州当前晴，28°C");

        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = readBody(((MockClientHttpRequest) request).getBodyAsBytes());
                    assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
                    assertThat(body.path("tools").get(0).path("function").path("name").asText())
                            .isEqualTo(WeatherTool.NAME);
                })
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "finish_reason": "tool_calls",
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call_weather_1",
                                "type": "function",
                                "function": {
                                  "name": "get_current_weather",
                                  "arguments": "{\\\"city\\\":\\\"杭州\\\"}"
                                }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = readBody(((MockClientHttpRequest) request).getBodyAsBytes());
                    JsonNode messages = body.path("messages");
                    JsonNode assistant = messages.get(messages.size() - 2);
                    JsonNode tool = messages.get(messages.size() - 1);

                    assertThat(assistant.path("role").asText()).isEqualTo("assistant");
                    assertThat(assistant.path("tool_calls").get(0).path("id").asText())
                            .isEqualTo("call_weather_1");
                    assertThat(tool.path("role").asText()).isEqualTo("tool");
                    assertThat(tool.path("tool_call_id").asText()).isEqualTo("call_weather_1");
                    assertThat(tool.path("content").asText()).isEqualTo("杭州当前晴，28°C");
                })
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {
                              "role": "assistant",
                              "content": "杭州现在晴，气温28°C。"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        String reply = llmService.chat("user-1", "杭州现在天气怎么样？");

        assertThat(reply).isEqualTo("杭州现在晴，气温28°C。");
        verify(weatherService).getWeather("杭州");
        server.verify();
    }

    private JsonNode readBody(byte[] bytes) throws IOException {
        return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
    }
}
