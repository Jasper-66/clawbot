package com.example.clawbot.resume.tool;

import com.example.clawbot.resume.service.ResumeOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 暴露给模型的岗位搜索和投递工具。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeTool {

    private final ResumeOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    @Tool(name = "search_jobs",
            description = "按岗位关键词和城市搜索猎聘职位，并保存本次结果。用户查询工作或岗位时调用。")
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
        log.info("[行动] LLM调用工具: search_jobs");

        try {
            String result = orchestrator.searchJobs(effectiveUserId, keyword.trim(), city.trim());
            log.info("[观察] search_jobs 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] search_jobs 失败 | 原因: {} | 建议: 检查招聘平台API连接", e.getMessage(), e);
            return error("搜索岗位失败: " + e.getMessage());
        }
    }

    @Tool(name = "prepare_job_application",
            description = "准备投递但不真正提交。支持“投1号”“投1和3号”“全部投递”；优先使用用户最近一次 search_jobs 的结果。")
    public String prepareApplication(
            @ToolParam(description = "用户原始消息，包含完整求职需求（期望岗位、城市、薪资等）") String user_message,
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        if (user_message == null || user_message.trim().isEmpty()) {
            return error("请提供求职需求描述");
        }
        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: prepare_job_application");
        log.info("  用户原始需求: \"{}\"", user_message.length() > 100
                ? user_message.substring(0, 100) + "..." : user_message);

        try {
            return orchestrator.prepareApplication(effectiveUserId, user_message.trim());
        } catch (Exception e) {
            log.error("[异常] 准备投递失败 | 用户: {} | 原因: {}",
                    effectiveUserId, e.getMessage(), e);
            return error("准备投递失败: " + e.getMessage());
        }
    }

    @Tool(name = "confirm_job_application",
            description = "仅当用户看到待确认清单后明确确认时调用，执行该用户最新的待确认投递任务。")
    public String confirmApplication(
            @ToolParam(description = "当前微信用户的唯一标识") String user_id) {

        if (user_id == null || user_id.isBlank()) {
            return error("缺少用户标识，无法查找待确认任务");
        }
        String effectiveUserId = user_id.trim();

        try {
            log.info("[行动] LLM调用工具: confirm_job_application | 用户: {}", effectiveUserId);
            return orchestrator.confirmApplication(effectiveUserId);
        } catch (Exception e) {
            log.error("[异常] 确认投递失败 | 用户: {} | 原因: {}",
                    effectiveUserId, e.getMessage(), e);
            return error("投递失败: " + e.getMessage());
        }
    }

    @Tool(name = "get_application_progress", description = "查询求职投递进度。用户问投了多少家、求职进度怎么样时调用。")
    public String getApplicationProgress(
            @ToolParam(required = false, description = "用户唯一标识") String user_id) {

        String effectiveUserId = user_id != null ? user_id.trim() : "unknown_user";
        log.info("[行动] LLM调用工具: get_application_progress");

        try {
            String result = orchestrator.getApplicationProgress(effectiveUserId);
            log.info("[观察] get_application_progress 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] 查询投递进度失败 | 用户: {} | 原因: {}", effectiveUserId, e.getMessage(), e);
            return error("查询失败: " + e.getMessage());
        }
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "message", message));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"" + message + "\"}";
        }
    }
}
