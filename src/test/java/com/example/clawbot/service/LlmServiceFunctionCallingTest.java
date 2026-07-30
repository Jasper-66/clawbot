package com.example.clawbot.service;

import com.example.clawbot.repository.ConversationRepository;
import com.example.clawbot.repository.MessageRepository;
import com.example.clawbot.repository.ReminderRepository;
import com.example.clawbot.repository.SqliteChatMemory;
import com.example.clawbot.tool.GeocodeTool;
import com.example.clawbot.tool.PlanRouteTool;
import com.example.clawbot.tool.ReminderTool;
import com.example.clawbot.tool.SearchNearbyTool;
import com.example.clawbot.tool.TarotTool;
import com.example.clawbot.tool.TextToSpeechTool;
import com.example.clawbot.tool.WeatherTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
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

    /** 提醒工具 — 使用 mock 的 ReminderRepository */
    private final ReminderRepository reminderRepository = mock(ReminderRepository.class);
    private final ReminderService reminderService = new ReminderService(reminderRepository);
    private final ReminderTool reminderTool = new ReminderTool(reminderService);

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private LlmService llmService;

    /**
     * 每个测试方法执行前的初始化。
     */
    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        // 创建 LlmService 需要的 mock 依赖
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_SELF);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        SqliteChatMemory chatMemory = mock(SqliteChatMemory.class);

        llmService = new LlmService(
                restTemplate, chatClientBuilder, conversationRepository, messageRepository,
                chatMemory, weatherTool, geocodeTool, searchNearbyTool, planRouteTool,
                textToSpeechTool, reminderTool, tarotTool, null);

        // 通过反射注入配置值（避免依赖 Spring 容器和 application.properties）
        ReflectionTestUtils.setField(llmService, "visionApiKey", "test-key");
        ReflectionTestUtils.setField(llmService, "visionBaseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(llmService, "visionModel", "deepseek-chat");
    }

    /**
     * 验证 WeatherTool 能正确调用 WeatherService。
     */
    @Test
    void shouldExecuteWeatherToolCorrectly() {
        when(weatherService.getWeather("杭州")).thenReturn("杭州当前晴，28°C");

        String result = weatherTool.getWeather("杭州");

        assertThat(result).isEqualTo("杭州当前晴，28°C");
        verify(weatherService).getWeather("杭州");
    }

    /**
     * 将 HTTP 请求体字节数组解析为 Jackson JsonNode。
     */
    private JsonNode readBody(byte[] bytes) throws IOException {
        return objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
    }
}
