package com.example.clawbot.service;

import com.example.clawbot.memory.JpaChatMemory;
import com.example.clawbot.tool.*;
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

/**
 * LlmService Function Calling 集成测试。
 *
 * <p>使用 Spring 的 {@link MockRestServiceServer} 模拟 DeepSeek API 的 HTTP 响应，
 * 验证完整的 Function Calling 流程：LLM 返回 tool_calls → 执行工具 → 将结果回传给 LLM。</p>
 */
class LlmServiceFunctionCallingTest {

    /** DeepSeek Chat Completion API 端点 */
    private static final String CHAT_URL = "https://api.deepseek.com/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** WeatherService mock — 避免真实 HTTP 调用心知天气 API */
    private final WeatherService weatherService = mock(WeatherService.class);

    /** WeatherTool 使用真实实例（但其依赖的 WeatherService 已 mock） */
    private final WeatherTool weatherTool = new WeatherTool(weatherService);

    /** 以下 Tool 均为 mock，本测试用例不涉及它们的功能 */
    private final GeocodeTool geocodeTool = mock(GeocodeTool.class);
    private final SearchNearbyTool searchNearbyTool = mock(SearchNearbyTool.class);
    private final PlanRouteTool planRouteTool = mock(PlanRouteTool.class);
    private final TextToSpeechTool textToSpeechTool = mock(TextToSpeechTool.class);
    private final TarotTool tarotTool = mock(TarotTool.class);
    private final DateTimeTool dateTimeTool = mock(DateTimeTool.class);
    private final RemindTool remindTool = mock(RemindTool.class);
    private final ScheduledTaskTool scheduledTaskTool = mock(ScheduledTaskTool.class);
    private final ImageGenerationTool imageGenerationTool = mock(ImageGenerationTool.class);
    private final JpaChatMemory chatMemory = mock(JpaChatMemory.class);

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private LlmService llmService;

    /**
     * 每个测试方法执行前的初始化。
     *
     * <p>创建真实的 RestTemplate 并用 MockRestServiceServer 包装，
     * 然后手动构造 LlmService 并注入测试用的 API 配置。</p>
     */
    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        llmService = new LlmService(restTemplate, chatMemory, weatherTool, dateTimeTool,
                textToSpeechTool, geocodeTool, searchNearbyTool, planRouteTool,
                tarotTool, remindTool, scheduledTaskTool, imageGenerationTool);

        // 通过反射注入配置值（避免依赖 Spring 容器和 application.properties）
        ReflectionTestUtils.setField(llmService, "apiKey", "test-key");
        ReflectionTestUtils.setField(llmService, "baseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(llmService, "model", "deepseek-chat");
    }

    /**
     * 验证完整的 Function Calling 流程：工具调用 → 结果回传 → 最终回复。
     *
     * <p>Mock 两次 API 调用：</p>
     * <ul>
     *   <li>第一次返回 tool_calls（get_weather, city=杭州）</li>
     *   <li>第二次返回最终文本回复</li>
     * </ul>
     */
    @Test
    void shouldExecuteWeatherToolAndSendResultBackToModel() {
        // 预设 WeatherService 的返回值
        when(weatherService.getWeather("杭州")).thenReturn("杭州当前晴，28°C");

        // ── 第 1 次 API 调用：LLM 返回 tool_calls ──
        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = readBody(((MockClientHttpRequest) request).getBodyAsBytes());
                    assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
                    assertThat(body.path("tools").get(0).path("function").path("name").asText())
                            .isEqualTo(weatherTool.getToolName());
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
                                  "name": "get_weather",
                                  "arguments": "{\\"city\\":\\"杭州\\"}"
                                }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        // ── 第 2 次 API 调用：工具结果回传后 LLM 返回最终回答 ──
        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = readBody(((MockClientHttpRequest) request).getBodyAsBytes());
                    JsonNode messages = body.path("messages");

                    // assistant 消息（倒数第 2 条）— 包含 tool_calls
                    JsonNode assistant = messages.get(messages.size() - 2);
                    assertThat(assistant.path("role").asText()).isEqualTo("assistant");
                    assertThat(assistant.path("tool_calls").get(0).path("id").asText())
                            .isEqualTo("call_weather_1");

                    // tool 消息（最后一条）— 包含工具执行结果
                    JsonNode tool = messages.get(messages.size() - 1);
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

        // 执行对话
        String reply = llmService.chat("user-1", "杭州现在天气怎么样？");

        // 验证最终回复
        assertThat(reply).isEqualTo("杭州现在晴，气温28°C。");

        // 验证 WeatherService 被正确调用
        verify(weatherService).getWeather("杭州");

        // 验证所有预期的 API 请求都已发送
        server.verify();
    }

    /**
     * 将 HTTP 请求体字节数组解析为 Jackson JsonNode。
     */
    private JsonNode readBody(byte[] bytes) throws IOException {
        return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
    }
}
