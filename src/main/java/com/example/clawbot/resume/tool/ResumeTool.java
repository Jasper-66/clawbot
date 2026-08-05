package com.example.clawbot.resume.tool;

import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.service.ResumeOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
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
    // ═══════════════════════════════════════════════════
    @Tool(name = "search_jobs", description = "从猎聘平台实时搜索最新招聘岗位。当用户表达了找工作/查岗位/搜职位的意图时，必须优先调用本工具，不要自己编造岗位列表。触发关键词包括但不限于：帮我看看xx的岗位、xx有什么工作、找一下xx的职位、搜一下xx岗位、xx地区有什么xx工作、帮我查一下xx岗位、有没有xx的工作。注意：必须调用本工具获取真实数据，绝对不能直接回复编造的岗位信息。")
    public String searchJobs(
            @ToolParam(description = "岗位关键词，如 Java开发、产品经理、AI、后端开发") String keyword,
            @ToolParam(description = "城市名称，如 北京、上海、河南、河北、杭州、全国") String city,
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
    @Tool(name = "auto_apply", description = "自动投递简历。当用户表达了求职意图（想找XX工作、帮我投简历）时调用此工具。注意：如果用户刚搜索过岗位并说\"投这几个\"、\"投第几个\"、\"就投这些\"、\"投列表中的\"，请使用 apply_from_search 而不是本工具。")
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
            if (result.getJobListing() != null) {
                log.info("[最终结果] 投递: company={}, job={}, matchScore={}, success={}",
                        result.getJobListing().getCompany(), result.getJobListing().getTitle(),
                        result.getMatchScore(), result.isSuccess());
            } else {
                log.info("[最终结果] 投递完成，无岗位详情");
            }
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
    // 工具3: 从搜索结果中投递指定岗位
    // ═══════════════════════════════════════════════════
    @Tool(name = "apply_from_search", description = "用户搜索岗位后，从搜索结果列表中选择指定岗位投递。当用户说投第几个、投前N个、投这几个、投这些、就投这些、投列表中的时调用。indices是岗位序号列表（从1开始），count是投前几个。如果用户刚搜索过岗位并表达了投递意向，请优先使用本工具而非 auto_apply。")
    public String applyFromSearch(
            @ToolParam(required = false, description = "岗位序号列表，从1开始，如 [1,3,5] 表示投第1、3、5个") List<Integer> indices,
            @ToolParam(required = false, description = "投前N个，如 5 表示投前5个") Integer count,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: apply_from_search | 用户: {} | indices={} | count={}",
                effectiveUserId, indices, count);

        if ((indices == null || indices.isEmpty()) && (count == null || count <= 0)) {
            return error("请指定要投递的岗位序号，或者说\"投前3个\"");
        }

        try {
            String result = orchestrator.applyFromSearch(effectiveUserId, indices, count);
            log.info("[观察] apply_from_search 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] apply_from_search 失败 | 用户: {} | 原因: {}", effectiveUserId, e.getMessage(), e);
            return error("从搜索结果投递失败: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具4: 查询投递进度
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
     */
    public static String formatApplyResult(ApplicationResult result) {
        if (result == null) {
            return "❌ 投递失败，无返回结果";
        }

        boolean isMock = !result.isRealSuccess() && result.getDeliveryMethod() != null
                && "MOCK".equals(result.getDeliveryMethod());

        StringBuilder sb = new StringBuilder();
        if (result.getResumeSummary() != null && !result.getResumeSummary().isBlank()) {
            sb.append(result.getResumeSummary()).append("\n\n");
        }

        // 待确认模式：尚未投递，message 为候选岗位列表，直接展示给用户选择
        if (!result.isSuccess() && !isMock && result.getMessage() != null && !result.getMessage().isBlank()) {
            sb.append(result.getMessage());
            return sb.toString();
        }

        String jobTitle = result.getJobListing() != null ? result.getJobListing().getTitle() : "未知岗位";
        String company = result.getJobListing() != null ? result.getJobListing().getCompany() : "未知公司";
        String salary = result.getJobListing() != null ? result.getJobListing().getSalary() : "";
        String city = result.getJobListing() != null ? result.getJobListing().getCity() : "";
        int matchScore = result.getMatchScore();

        if (result.isSuccess() && !isMock) {
            sb.append("✅ 投递成功！\n");
        } else if (isMock) {
            sb.append("⚠️ 投递未生效（模拟模式）\n");
            sb.append("原因：猎聘 Cookie/Token 未配置或已过期\n");
            sb.append("请登录猎聘官网获取新的 Cookie/Token 后重试\n\n");
        } else {
            sb.append("❌ 投递失败\n");
        }

        sb.append(String.format("📋 %s @ %s\n", jobTitle, company));
        if (salary != null && !salary.isEmpty()) sb.append(String.format("💰 %s\n", salary));
        if (city != null && !city.isEmpty()) sb.append(String.format("📍 %s\n", city));
        sb.append(String.format("📊 匹配度: %d分\n", matchScore));

        if (result.getMessage() != null && !result.getMessage().isEmpty()) {
            sb.append(String.format("📝 %s\n", result.getMessage()));
        }
        if (result.getOptimizationTip() != null && !result.getOptimizationTip().isEmpty()) {
            sb.append(String.format("💡 %s\n", result.getOptimizationTip()));
        }
        if (result.getApplicationId() != null && !result.getApplicationId().isEmpty()) {
            sb.append(String.format("🎫 投递编号：%s\n", result.getApplicationId()));
        }
        if (result.getDeliveryMethod() != null) {
            sb.append(String.format("🔧 投递方式：%s\n", result.getDeliveryMethod()));
        }

        return sb.toString();
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "message", message));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"" + message + "\"}";
        }
    }
}
