package com.example.clawbot.tool;

import com.example.clawbot.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 天气查询工具 — LLM 可调用的实时天气查询能力。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code get_weather}。
 * 设计遵循"工具-服务分离"模式：</p>
 * <ul>
 *   <li><b>WeatherTool</b>（本类）— 负责向 LLM 描述工具定义、参数校验、
 *       将 LLM 的参数路由到 WeatherService</li>
 *   <li><b>WeatherService</b> — 负责实际的 HTTP 调用、API 响应解析、
 *       错误处理和格式化输出</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <p>当用户询问天气时，LLM 自动调用此工具：</p>
 * <pre>
 * 用户："北京今天天气怎么样？"
 *   → LLM 检测到天气意图
 *   → LLM 调用 get_weather(city="北京")
 *   → WeatherTool 委托 WeatherService 查询心知天气 API
 *   → 返回 "📍 北京 当前天气\n☁️ 天气：晴\n🌡 温度：25°C\n..."
 * </pre>
 *
 * <h3>安全</h3>
 * <p>城市名长度限制为 {@value #MAX_CITY_LENGTH} 字符，防止恶意超长输入。
 * 参数校验在 Tool 层完成，Service 层可直接信任参数格式。</p>
 *
 * @see com.example.clawbot.service.WeatherService
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    /** 天气查询核心服务 */
    private final WeatherService weatherService;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具名称，对应 LLM Function Calling 的 function.name */
    private static final String NAME = "get_weather";

    /** 城市名最大长度，防止恶意输入 */
    private static final int MAX_CITY_LENGTH = 50;

    /**
     * 工具描述，供 LLM 理解何时调用此工具。
     *
     * <p>当用户询问某个城市的天气时，LLM 应根据此描述自动选择此工具。</p>
     */
    private static final String DESCRIPTION = "查询指定城市的实时天气信息，包括天气状况、温度和更新时间。当用户询问某个城市的天气时使用此工具。";

    /**
     * 获取工具名称。
     *
     * <p>用于在 {@link com.example.clawbot.service.LlmService#executeTool} 中
     * 按名称路由到正确的 Tool 实例。</p>
     *
     * @return 工具标识名 "get_weather"
     */
    public String getToolName() {
        return NAME;
    }

    /**
     * Spring AI @Tool 方法 — 供 ChatClient 自动注册为 Function Callback。
     */
    @Tool(name = "get_weather", description = "查询指定城市的实时天气信息，包括天气状况、温度和更新时间。当用户询问某个城市的天气时使用此工具。")
    public String getWeather(
            @ToolParam(required = true, description = "城市名称，例如：北京、上海、深圳") String city) {
        if (city == null || city.trim().isEmpty()) {
            return "工具调用失败：city 参数不能为空";
        }
        if (city.length() > MAX_CITY_LENGTH) {
            return "工具调用失败：city 参数过长";
        }
        log.info("执行天气工具: city={}", city);
        return weatherService.getWeather(city.trim());
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * <p>向 LLM 描述：</p>
     * <ul>
     *   <li>工具名称（name）— LLM 调用时使用的标识</li>
     *   <li>功能描述（description）— 帮助 LLM 判断是否应该使用此工具</li>
     *   <li>参数 Schema（parameters）— JSON Schema 格式，定义参数类型和是否必填</li>
     * </ul>
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>{@code city}（必填）— 城市名称，LLM 从用户消息中提取，如 "北京"、"上海"</li>
     * </ul>
     *
     * @return Function Calling 格式的工具定义 Map
     */
    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "city", Map.of(
                                                "type", "string",
                                                "description", "城市名称，例如：北京、上海、深圳、杭州、成都、东京、纽约等"
                                        )
                                ),
                                "required", List.of("city")
                        )
                )
        );
    }

    /**
     * 校验并执行 LLM 请求的工具调用。
     *
     * <p>职责：</p>
     * <ol>
     *   <li>校验 functionName 是否匹配（防止路由错误）</li>
     *   <li>解析 LLM 生成的参数 JSON</li>
     *   <li>校验必填参数：city 非空</li>
     *   <li>校验参数长度：city 不超过 {@value #MAX_CITY_LENGTH} 字符</li>
     *   <li>委托 {@link WeatherService#getWeather} 执行实际查询</li>
     * </ol>
     *
     * <p>异常均被捕获并转为友好提示，不会因工具调用失败而中断整个对话流程。</p>
     *
     * @param functionName  LLM 返回的工具名称（应为 "get_weather"）
     * @param argumentsJson LLM 生成的参数 JSON 字符串（如 {@code {"city":"北京"}}）
     * @return 天气查询结果文本（含 emoji 格式），或错误说明
     */
    public String execute(String functionName, String argumentsJson) {
        // 路由校验
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            // 解析参数 JSON
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String city = arguments.path("city").asText("").trim();

            // 参数校验：非空
            if (city.isEmpty()) {
                return "工具调用失败：city 参数不能为空";
            }

            // 参数校验：长度限制
            if (city.length() > MAX_CITY_LENGTH) {
                return "工具调用失败：city 参数过长";
            }

            log.info("执行天气工具: city={}", city);
            // 委托 WeatherService 执行真实查询
            return weatherService.getWeather(city);
        } catch (Exception e) {
            log.error("天气工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
