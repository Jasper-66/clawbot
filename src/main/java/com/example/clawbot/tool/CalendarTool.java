package com.example.clawbot.tool;

import com.example.clawbot.service.CalendarService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 日历查询工具 — LLM 可调用的黄历/节假日/放假安排查询能力。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code calendar_query}。
 * 设计遵循"工具-服务分离"模式：</p>
 * <ul>
 *   <li><b>CalendarTool</b>（本类）— 负责向 LLM 描述工具定义、参数校验、
 *       将 LLM 的参数路由到 CalendarService</li>
 *   <li><b>CalendarService</b> — 负责实际的 HTTP 调用、API 响应解析、
 *       错误处理和格式化输出</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <p>当用户询问日历相关信息时，LLM 自动调用此工具：</p>
 * <pre>
 * 用户："今天适合搬家吗？"
 *   → LLM 检测到黄历查询意图
 *   → LLM 调用 calendar_query(query_type="almanac", date="2024-01-15")
 *   → CalendarTool 委托 CalendarService 查询聚合数据老黄历 API
 *   → 返回 "📅 2024-01-15 黄历信息\n🌙 农历：腊月初五\n✅ 宜：..."
 *
 * 用户："国庆节放几天假？"
 *   → LLM 调用 calendar_query(query_type="schedule", date="2024-10-01")
 *   → CalendarService 查询 Timor.tech 放假安排 API
 *   → 返回 "📅 2024年放假安排\n🎉 国庆节：10月1日-7日，共7天"
 * </pre>
 *
 * @see com.example.clawbot.service.CalendarService
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarTool {

    /** 日历查询核心服务 */
    private final CalendarService calendarService;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具名称，对应 LLM Function Calling 的 function.name */
    private static final String NAME = "calendar_query";

    /** 支持的查询类型 */
    private static final List<String> VALID_QUERY_TYPES = List.of("almanac", "holiday", "schedule");

    /**
     * 工具描述，供 LLM 理解何时调用此工具。
     *
     * <p>当用户询问黄历、节假日或放假安排时，LLM 应根据此描述自动选择此工具。</p>
     */
    private static final String DESCRIPTION = "查询日历相关信息，支持三种查询类型：\n" +
            "1. almanac - 查询指定日期的黄历信息（宜忌、冲煞、吉凶等），适用于「今天适合做什么」「黄历查询」等\n" +
            "2. holiday - 查询指定日期是否为节假日或工作日（含调休），适用于「今天放假吗」「明天上班吗」等\n" +
            "3. schedule - 查询某年的放假安排，适用于「今年放假安排」「国庆放几天」等";

    /**
     * 获取工具名称。
     *
     * <p>用于在 {@link com.example.clawbot.service.LlmService#executeTool} 中
     * 按名称路由到正确的 Tool 实例。</p>
     *
     * @return 工具标识名 "calendar_query"
     */
    public String getToolName() {
        return NAME;
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>{@code query_type}（必填）— 查询类型：almanac（黄历）、holiday（节假日）、schedule（放假安排）</li>
     *   <li>{@code date}（选填）— 日期字符串，格式 yyyy-MM-dd，默认为今天。
     *       schedule 类型会自动提取年份</li>
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
                                        "query_type", Map.of(
                                                "type", "string",
                                                "description", "查询类型：almanac（黄历宜忌）、holiday（节假日查询）、schedule（年度放假安排）",
                                                "enum", List.of("almanac", "holiday", "schedule")
                                        ),
                                        "date", Map.of(
                                                "type", "string",
                                                "description", "日期，格式 yyyy-MM-dd，如 2024-10-01。不填则默认为今天。schedule 类型会自动提取年份。"
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
     * <p>职责：</p>
     * <ol>
     *   <li>校验 functionName 是否匹配</li>
     *   <li>解析并校验 query_type 参数（必填、枚举校验）</li>
     *   <li>解析 date 参数（选填，默认今天）</li>
     *   <li>根据 query_type 路由到 CalendarService 对应方法</li>
     * </ol>
     *
     * @param functionName  LLM 返回的工具名称（应为 "calendar_query"）
     * @param argumentsJson LLM 生成的参数 JSON 字符串
     * @return 日历查询结果文本，或错误说明
     */
    public String execute(String functionName, String argumentsJson) {
        // 路由校验
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);

            // 解析 query_type（必填）
            String queryType = arguments.path("query_type").asText("").trim();
            if (queryType.isEmpty()) {
                return "工具调用失败：query_type 参数不能为空，可选值：almanac、holiday、schedule";
            }
            if (!VALID_QUERY_TYPES.contains(queryType)) {
                return "工具调用失败：不支持的查询类型「" + queryType + "」，可选值：almanac、holiday、schedule";
            }

            // 解析 date（选填，默认今天）
            String date = arguments.path("date").asText("").trim();
            if (date.isEmpty()) {
                date = java.time.LocalDate.now().toString(); // yyyy-MM-dd
            }

            // 校验日期格式
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return "工具调用失败：日期格式不正确，应为 yyyy-MM-dd，如 2024-10-01";
            }

            log.info("执行日历查询工具: queryType={}, date={}", queryType, date);

            // 根据查询类型路由
            return switch (queryType) {
                case "almanac" -> calendarService.queryAlmanac(date);
                case "holiday" -> calendarService.queryHoliday(date);
                case "schedule" -> calendarService.querySchedule(date.substring(0, 4));
                default -> "工具调用失败：不支持的查询类型 " + queryType;
            };
        } catch (Exception e) {
            log.error("日历查询工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
