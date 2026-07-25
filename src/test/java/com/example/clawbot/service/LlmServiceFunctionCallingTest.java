package com.example.clawbot.service;

import com.example.clawbot.tool.GeocodeTool;
import com.example.clawbot.tool.PlanRouteTool;
import com.example.clawbot.tool.SearchNearbyTool;
import com.example.clawbot.tool.CalendarTool;
import com.example.clawbot.tool.TarotTool;
import com.example.clawbot.tool.TextToSpeechTool;
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

/**
 * LlmService Function Calling 集成测试。
 *
 * <p>使用 Spring 的 {@link MockRestServiceServer} 模拟 DeepSeek API 的 HTTP 响应，
 * 验证完整的 Function Calling 流程：LLM 返回 tool_calls → 执行工具 → 将结果回传给 LLM。</p>
 *
 * <h3>测试架构</h3>
 * <p>通过 MockRestServiceServer 拦截 {@link RestTemplate} 的 HTTP 请求并返回预设 JSON，
 * 无需真实网络连接。除 WeatherService 外，其余 7 个 Tool 均使用 Mockito mock，
 * 因为该测试用例仅验证天气工具的调用链路。</p>
 *
 * <h3>测试场景：天气查询</h3>
 * <p>模拟用户问"杭州现在天气怎么样？"的完整处理流程：</p>
 * <ol>
 *   <li>第 1 次 API 调用 — LLM 返回 tool_calls（finish_reason=tool_calls），
 *       请求调用 get_weather 工具，参数 city="杭州"</li>
 *   <li>LlmService 执行工具路由 → WeatherTool → WeatherService.getWeather("杭州")
 *       → 返回 "杭州当前晴，28°C"</li>
 *   <li>第 2 次 API 调用 — LlmService 将工具结果回传给 LLM，
 *       LLM 返回最终回答（finish_reason=stop）</li>
 * </ol>
 *
 * <p><b>注意</b>：此测试使用大写 JSON 键名（如 "杭州" 而非 "\\u676d\\u5dde"），
 * 因为 MockRestServiceServer 返回的是 Java 文本块字符串，由 Jackson 直接解析。</p>
 */
class LlmServiceFunctionCallingTest {

    /** DeepSeek Chat Completion API 端点 */
    private static final String CHAT_URL = "https://api.deepseek.com/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** WeatherService mock — 避免真实 HTTP 调用心知天气 API */
    private final WeatherService weatherService = mock(WeatherService.class);

    /** WeatherTool 使用真实实例（但其依赖的 WeatherService 已 mock） */
    private final WeatherTool weatherTool = new WeatherTool(weatherService);

    /** 以下 7 个 Tool 均为 mock，本测试用例不涉及它们的功能 */
    private final GeocodeTool geocodeTool = mock(GeocodeTool.class);
    private final SearchNearbyTool searchNearbyTool = mock(SearchNearbyTool.class);
    private final PlanRouteTool planRouteTool = mock(PlanRouteTool.class);
    private final TextToSpeechTool textToSpeechTool = mock(TextToSpeechTool.class);
    private final TarotTool tarotTool = mock(TarotTool.class);
    private final CalendarTool calendarTool = mock(CalendarTool.class);

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
        llmService = new LlmService(restTemplate, weatherTool, geocodeTool, searchNearbyTool, planRouteTool, textToSpeechTool, tarotTool, calendarTool);

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
     *
     * <p>断言包括：</p>
     * <ul>
     *   <li>最终回复文本正确</li>
     *   <li>WeatherService.getWeather("杭州") 被调用了一次</li>
     *   <li>两次 API 请求均按预期发送</li>
     * </ul>
     */
    @Test
    void shouldExecuteWeatherToolAndSendResultBackToModel() {
        // 预设 WeatherService 的返回值
        when(weatherService.getWeather("杭州")).thenReturn("杭州当前晴，28°C");

        // ── 第 1 次 API 调用：LLM 返回 tool_calls ──
        // 验证请求包含 tool_choice=auto 和正确的工具注册
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
        // 验证 messages 中正确包含了助手工具调用消息和工具结果消息
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
     *
     * @param bytes 请求体字节数组
     * @return 解析后的 JSON 树
     * @throws IOException JSON 解析失败
     */
    private JsonNode readBody(byte[] bytes) throws IOException {
        return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
    }
}
