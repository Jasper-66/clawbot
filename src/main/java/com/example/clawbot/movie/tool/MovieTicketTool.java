package com.example.clawbot.movie.tool;

import com.example.clawbot.movie.model.TicketOrder;
import com.example.clawbot.movie.service.MovieTicketOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

// LLM 可调用的电影票工具 — 微信用户说"帮我买电影票"时由 DeepSeek Function Calling 触发
// 本类只做参数校验+日志，核心逻辑全部委托给 MovieTicketOrchestrator
@Slf4j
@Component
@RequiredArgsConstructor
public class MovieTicketTool {

    private final MovieTicketOrchestrator orchestrator;  // 成员7
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════
    // 工具1: 搜索电影
    // ═══════════════════════════════════════════════════
    @Tool(name = "search_movies", description = "搜索当前上映的电影列表。用户问有什么电影、最近有什么好看的电影时调用。")
    public String searchMovies(
            @ToolParam(description = "城市名称，如 北京、上海") String city,
            @ToolParam(required = false, description = "电影关键词，如 哪吒、科幻、喜剧") String keyword,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        if (city == null || city.trim().isEmpty()) {
            return error("请指定城市名称");
        }
        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: search_movies → 成员7 编排搜索流程");

        try {
            String result = orchestrator.searchMovies(effectiveUserId, city.trim(),
                    keyword != null ? keyword.trim() : "");
            log.info("[观察] search_movies 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] search_movies 失败 | 原因: {} | 建议: 检查电影票平台API连接", e.getMessage(), e);
            return error("搜索电影失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具2: 查询场次
    // ═══════════════════════════════════════════════════
    @Tool(name = "search_showtimes", description = "查询指定电影的放映场次。用户选中电影后、要选时间时调用。")
    public String searchShowtimes(
            @ToolParam(description = "电影ID") String movie_id,
            @ToolParam(description = "城市名称") String city,
            @ToolParam(required = false, description = "日期，如 2026-08-01，不填默认今天") String date) {

        if (movie_id == null || movie_id.trim().isEmpty()) {
            return error("请指定电影ID（从 search_movies 结果中获取）");
        }
        log.info("[行动] LLM调用工具: search_showtimes → 成员7 编排场次查询");

        try {
            String result = orchestrator.searchShowtimes(movie_id.trim(), city.trim(),
                    date != null ? date.trim() : "");
            log.info("[观察] search_showtimes 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] search_showtimes 失败 | 原因: {}", e.getMessage(), e);
            return error("查询场次失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具3: 一键购票（核心）
    // ═══════════════════════════════════════════════════
    @Tool(name = "purchase_ticket", description = "自动购买电影票。当用户完整表达了购票意图（电影+时间+数量）时调用此工具。")
    public String purchaseTicket(
            @ToolParam(description = "用户原始消息，包含完整购票需求（电影名、日期、时间、数量、位置偏好）") String user_message,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        if (user_message == null || user_message.trim().isEmpty()) {
            return error("请提供购票需求描述");
        }
        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: purchase_ticket → 成员7 全流程自动购票 (Step1~8)");
        log.info("  用户原始需求: \"{}\"", user_message.length() > 100
                ? user_message.substring(0, 100) + "..." : user_message);

        try {
            TicketOrder order = orchestrator.autoPurchase(effectiveUserId, user_message.trim());
            String result = formatOrderResult(order);
            log.info("[最终结果] 购票成功: orderId={}, movie={}, seats={}",
                    order.getOrderId(), order.getMovie().getTitle(), order.getSeats().size());
            return result;
        } catch (UnsupportedOperationException e) {
            log.warn("[观察] 购票功能尚未实现: {}", e.getMessage());
            return error("购票功能开发中，敬请期待！");
        } catch (Exception e) {
            log.error("[异常] 购票失败 | 用户: {} | 原因: {} | 建议: 检查各成员模块是否正常",
                    effectiveUserId, e.getMessage(), e);
            return error("购票失败: " + e.getMessage());
        }
    }

    /**
     * 格式化订单结果为微信可读文本。
     *
     * 【被谁调用】purchaseTicket() 成功后
     * 【返回值】  含emoji的格式化购票成功文本
     */
    private String formatOrderResult(TicketOrder order) {
        return String.format(
                "✅ 购票成功！\n🎬 《%s》\n🏛 %s\n⏰ %s %s\n💺 %s\n💰 ¥%.2f\n🎫 取票码：%s",
                order.getMovie().getTitle(),
                order.getCinema().getName(),
                order.getShowtime().getShowDate(),
                order.getShowtime().getShowTime(),
                order.getSeats().stream().map(s -> s.getName()).reduce((a, b) -> a + "、" + b).orElse(""),
                order.getTotalPrice(),
                order.getTicketCode()
        );
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "message", message));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"" + message + "\"}";
        }
    }
}
