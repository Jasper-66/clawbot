package com.example.clawbot.tool;

import com.example.clawbot.service.CalendarService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CalendarTool 单元测试。
 *
 * <p>验证日历查询工具的三大职责：</p>
 * <ol>
 *   <li>暴露正确的 Function Calling 工具定义（名称、参数 Schema）</li>
 *   <li>参数校验与路由：正确参数委托给 CalendarService，非法参数返回错误</li>
 *   <li>三种查询类型（almanac/holiday/schedule）的路由正确性</li>
 * </ol>
 *
 * <p>使用 Mockito mock CalendarService，隔离 Tool 层逻辑。</p>
 */
class CalendarToolTest {

    private final CalendarService calendarService = mock(CalendarService.class);
    private final CalendarTool calendarTool = new CalendarTool(calendarService);

    /**
     * 验证工具定义包含正确的名称和参数。
     */
    @Test
    void shouldExposeFunctionDefinition() {
        Map<String, Object> definition = calendarTool.getToolDefinition();

        assertThat(definition.get("type")).isEqualTo("function");
        assertThat(definition.toString()).contains(calendarTool.getToolName(), "query_type", "date");
    }

    /**
     * 验证黄历查询路由正确。
     */
    @Test
    void shouldRouteToAlmanacQuery() {
        when(calendarService.queryAlmanac("2024-01-15")).thenReturn("📅 2024-01-15 黄历信息");

        String result = calendarTool.execute(calendarTool.getToolName(),
                "{\"query_type\":\"almanac\",\"date\":\"2024-01-15\"}");

        assertThat(result).isEqualTo("📅 2024-01-15 黄历信息");
        verify(calendarService).queryAlmanac("2024-01-15");
    }

    /**
     * 验证节假日查询路由正确。
     */
    @Test
    void shouldRouteToHolidayQuery() {
        when(calendarService.queryHoliday("2024-10-01")).thenReturn("📅 2024-10-01 节假日信息");

        String result = calendarTool.execute(calendarTool.getToolName(),
                "{\"query_type\":\"holiday\",\"date\":\"2024-10-01\"}");

        assertThat(result).isEqualTo("📅 2024-10-01 节假日信息");
        verify(calendarService).queryHoliday("2024-10-01");
    }

    /**
     * 验证放假安排查询路由正确（从 date 中提取年份）。
     */
    @Test
    void shouldRouteToScheduleQueryAndExtractYear() {
        when(calendarService.querySchedule("2024")).thenReturn("📅 2024年放假安排");

        String result = calendarTool.execute(calendarTool.getToolName(),
                "{\"query_type\":\"schedule\",\"date\":\"2024-10-01\"}");

        assertThat(result).isEqualTo("📅 2024年放假安排");
        verify(calendarService).querySchedule("2024");
    }

    /**
     * 验证 date 为空时使用默认日期（今天）。
     */
    @Test
    void shouldUseTodayWhenDateIsEmpty() {
        String today = java.time.LocalDate.now().toString();
        when(calendarService.queryHoliday(today)).thenReturn("节假日信息");

        String result = calendarTool.execute(calendarTool.getToolName(),
                "{\"query_type\":\"holiday\"}");

        verify(calendarService).queryHoliday(today);
    }

    /**
     * 验证参数校验：空 type、无效 type、无效日期格式、未知工具名。
     */
    @Test
    void shouldRejectInvalidArguments() {
        // 空 query_type
        assertThat(calendarTool.execute(calendarTool.getToolName(), "{}"))
                .contains("query_type 参数不能为空");

        // 无效 query_type
        assertThat(calendarTool.execute(calendarTool.getToolName(),
                "{\"query_type\":\"invalid\"}"))
                .contains("不支持的查询类型");

        // 无效日期格式
        assertThat(calendarTool.execute(calendarTool.getToolName(),
                "{\"query_type\":\"almanac\",\"date\":\"20240115\"}"))
                .contains("日期格式不正确");

        // 无效 JSON
        assertThat(calendarTool.execute(calendarTool.getToolName(), "not-json"))
                .contains("不是有效的 JSON");

        // 未知工具名
        assertThat(calendarTool.execute("unknown_tool", "{}"))
                .contains("不支持的工具");
    }
}
