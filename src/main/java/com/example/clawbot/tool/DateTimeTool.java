package com.example.clawbot.tool;

import com.example.clawbot.service.CalendarService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 日期时间查询工具 — LLM 可调用的时间/日历查询能力。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code datetime_query}。
 * 设计遵循"工具-服务分离"模式：</p>
 * <ul>
 *   <li><b>DateTimeTool</b>（本类）— 负责向 LLM 描述工具定义、参数校验、
 *       将 LLM 的参数路由到 CalendarService 或本地时间处理</li>
 *   <li><b>CalendarService</b> — 负责实际的 HTTP 调用、API 响应解析、
 *       错误处理和格式化输出</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <p>当用户询问时间、日期相关信息时，LLM 自动调用此工具：</p>
 * <pre>
 * 用户："现在几点了？"
 *   → LLM 检测到时间查询意图
 *   → LLM 调用 datetime_query(query_type="current_time")
 *   → 返回 "🕐 当前时间：2024-01-15 14:30:25 星期一"
 *
 * 用户："今天适合搬家吗？"
 *   → LLM 调用 datetime_query(query_type="almanac", date="2024-01-15")
 *   → DateTimeTool 委托 CalendarService 查询聚合数据老黄历 API
 *   → 返回 "📅 2024-01-15 黄历信息\n🌙 农历：腊月初五\n✅ 宜：..."
 * </pre>
 *
 * @see com.example.clawbot.service.CalendarService
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DateTimeTool {

    /** 日历查询核心服务 */
    private final CalendarService calendarService;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具名称，对应 LLM Function Calling 的 function.name */
    private static final String NAME = "datetime_query";

    /** 支持的查询类型 */
    private static final List<String> VALID_QUERY_TYPES = List.of("current_time", "almanac", "holiday", "schedule");

    /** 中文星期映射 */
    private static final String[] WEEK_DAYS = {"", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    /**
     * 工具描述，供 LLM 理解何时调用此工具。
     */
    private static final String DESCRIPTION = "查询日期、时间和日历相关信息，支持四种查询类型：\n" +
            "1. current_time - 获取当前的日期和时间，适用于「现在几点」「今天几号」「当前时间」等\n" +
            "2. almanac - 查询指定日期的黄历信息（宜忌、冲煞、吉凶等），适用于「今天适合做什么」「黄历查询」等\n" +
            "3. holiday - 查询指定日期是否为节假日或工作日（含调休），适用于「今天放假吗」「明天上班吗」等\n" +
            "4. schedule - 查询某年的放假安排，适用于「今年放假安排」「国庆放几天」等";

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "datetime_query"
     */
    public String getToolName() {
        return NAME;
    }

    @Tool(name = "datetime_query", description = "查询日期、时间和日历信息，支持四种查询类型：current_time（当前时间）、almanac（黄历宜忌）、holiday（节假日查询）、schedule（年度放假安排）")
    public String datetimeQuery(
            @ToolParam(required = true, description = "查询类型：current_time、almanac、holiday、schedule") String queryType,
            @ToolParam(required = false, description = "日期，格式 yyyy-MM-dd，如 2024-10-01。不填则默认为今天。current_time 类型不需要此参数") String date) {
        if (queryType == null || queryType.trim().isEmpty()) {
            return "工具调用失败：query_type 参数不能为空";
        }
        if (!VALID_QUERY_TYPES.contains(queryType)) {
            return "工具调用失败：不支持的查询类型「" + queryType + "」";
        }

        // current_time 不需要 date 参数，直接返回
        if ("current_time".equals(queryType)) {
            return getCurrentTime();
        }

        if (date == null || date.trim().isEmpty()) {
            date = java.time.LocalDate.now().toString();
        }
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return "工具调用失败：日期格式不正确，应为 yyyy-MM-dd";
        }
        log.info("执行日期时间查询工具: queryType={}, date={}", queryType, date);
        return switch (queryType) {
            case "almanac" -> calendarService.queryAlmanac(date);
            case "holiday" -> calendarService.queryHoliday(date);
            case "schedule" -> calendarService.querySchedule(date.substring(0, 4));
            default -> "不支持的查询类型: " + queryType;
        };
    }

    /**
     * 获取当前日期和时间。
     *
     * @return 格式化的当前时间字符串，如 "🕐 当前时间：2024-01-15 14:30:25 星期一"
     */
    private String getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String weekDay = WEEK_DAYS[now.getDayOfWeek().getValue()];
        return String.format("🕐 当前时间：%s %s", formatted, weekDay);
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
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
                                        "query_type", Map.of(
                                                "type", "string",
                                                "description", "查询类型：current_time（当前时间）、almanac（黄历宜忌）、holiday（节假日查询）、schedule（年度放假安排）",
                                                "enum", List.of("current_time", "almanac", "holiday", "schedule")
                                        ),
                                        "date", Map.of(
                                                "type", "string",
                                                "description", "日期，格式 yyyy-MM-dd，如 2024-10-01。不填则默认为今天。current_time 类型不需要此参数。"
                                        )
                                ),
                                "required", List.of("query_type")
                        )
                )
        );
    }

    /**
     * 校验并执行 LLM 请求的工具调用。
     *
     * @param functionName  LLM 返回的工具名称（应为 "datetime_query"）
     * @param argumentsJson LLM 生成的参数 JSON 字符串
     * @return 查询结果文本，或错误说明
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);

            String queryType = arguments.path("query_type").asText("").trim();
            if (queryType.isEmpty()) {
                return "工具调用失败：query_type 参数不能为空，可选值：current_time、almanac、holiday、schedule";
            }
            if (!VALID_QUERY_TYPES.contains(queryType)) {
                return "工具调用失败：不支持的查询类型「" + queryType + "」，可选值：current_time、almanac、holiday、schedule";
            }

            // current_time 不需要 date 参数
            if ("current_time".equals(queryType)) {
                return getCurrentTime();
            }

            String date = arguments.path("date").asText("").trim();
            if (date.isEmpty()) {
                date = java.time.LocalDate.now().toString();
            }

            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return "工具调用失败：日期格式不正确，应为 yyyy-MM-dd，如 2024-10-01";
            }

            log.info("执行日期时间查询工具: queryType={}, date={}", queryType, date);

            return switch (queryType) {
                case "almanac" -> calendarService.queryAlmanac(date);
                case "holiday" -> calendarService.queryHoliday(date);
                case "schedule" -> calendarService.querySchedule(date.substring(0, 4));
                default -> "工具调用失败：不支持的查询类型 " + queryType;
            };
        } catch (Exception e) {
            log.error("日期时间查询工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
