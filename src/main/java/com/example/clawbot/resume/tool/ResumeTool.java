package com.example.clawbot.resume.tool;

import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.service.ResumeOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

// LLM 可调用的简历投递工具 — 微信用户说"帮我投简历"时由 DeepSeek Function Calling 触发
// 本类只做参数校验+日志，核心逻辑全部委托给 ResumeOrchestrator
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeTool {

    private final ResumeOrchestrator orchestrator;  // 成员7
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════
    // 工具1: 搜索岗位
    // ════════════════
    // ═══════════════════════════════════
    @Tool(name = "search_jobs", description = "搜索匹配的招聘信息。用户问有什么合适的工作、帮我找找工作时调用。")
    public String searchJobs(
            @ToolParam(description = "岗位关键词，如 Java开发、产品经理") String keyword,
            @ToolParam(description = "城市名称，如 北京、上海") String city,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return error("请指定求职岗位关键词");
        }
        if (city == null || city.trim().isEmpty()) {
            return error("请指定城市名称");
        }
        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: search_jobs → 成员7 编排搜索流程");

        try {
            String result = orchestrator.searchJobs(effectiveUserId, keyword.trim(), city.trim());
            log.info("[观察] search_jobs 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] search_jobs 失败 | 原因: {} | 建议: 检查招聘平台API连接", e.getMessage(), e);
            return error("搜索岗位失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具2: 一键自动投递（核心）
    // ═══════════════════════════════════════════════════
    @Tool(name = "auto_apply", description = "自动投递简历。当用户表达了求职意图（想找XX工作、帮我投简历）时调用此工具。")
    public String autoApply(
            @ToolParam(description = "用户原始消息，包含完整求职需求（期望岗位、城市、薪资等）") String user_message,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        if (user_message == null || user_message.trim().isEmpty()) {
            return error("请提供求职需求描述");
        }
        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: auto_apply → 成员7 全流程自动投递 (Step1~7)");
        log.info("  用户原始需求: \"{}\"", user_message.length() > 100
                ? user_message.substring(0, 100) + "..." : user_message);

        try {
            ApplicationResult result = orchestrator.autoApply(effectiveUserId, user_message.trim());
            String formatted = formatApplyResult(result);
            log.info("[最终结果] 投递成功: company={}, job={}, matchScore={}",
                    result.getJobListing().getCompany(), result.getJobListing().getTitle(), result.getMatchScore());
            return formatted;
        } catch (UnsupportedOperationException e) {
            log.warn("[观察] 投递功能尚未实现: {}", e.getMessage());
            return error("投递功能开发中，敬请期待！");
        } catch (Exception e) {
            log.error("[异常] 投递失败 | 用户: {} | 原因: {} | 建议: 检查各成员模块是否正常",
                    effectiveUserId, e.getMessage(), e);
            return error("投递失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具3: 查询投递进度
    // ═══════════════════════════════════════════════════
    @Tool(name = "get_application_progress", description = "查询求职投递进度。用户问投了多少家、求职进度怎么样时调用。")
    public String getApplicationProgress(
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: get_application_progress → 成员7 查询投递记录");

        try {
            String result = orchestrator.getApplicationProgress(effectiveUserId);
            log.info("[观察] get_application_progress 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] 查询投递进度失败 | 用户: {} | 原因: {}", effectiveUserId, e.getMessage(), e);
            return error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 格式化投递结果为微信可读文本。
     *
     * 【被谁调用】autoApply() 成功后
     * 【返回值】  含emoji的格式化投递成功文本
     */
    private String formatApplyResult(ApplicationResult result) {
        return String.format(
                "✅ 投递成功！\n📋 %s @ %s\n💰 %s\n📍 %s\n📊 匹配度: %d分\n%s\n%s",
                result.getJobListing().getTitle(),
                result.getJobListing().getCompany(),
                result.getJobListing().getSalary(),
                result.getJobListing().getCity(),
                result.getMatchScore(),
                result.getOptimizationTip() != null ? "💡 " + result.getOptimizationTip() : "",
                result.getApplicationId() != null ? "🎫 投递编号：" + result.getApplicationId() : ""
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
