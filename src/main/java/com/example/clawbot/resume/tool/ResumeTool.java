package com.example.clawbot.resume.tool;

import com.example.clawbot.resume.service.ResumeOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
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
            ToolContext toolContext) {

        if (keyword == null || keyword.isBlank()) {
            return error("请指定求职岗位关键词");
        }
        if (city == null || city.isBlank()) {
            return error("请指定城市名称");
        }
        log.info("[行动] LLM调用工具: search_jobs");

        try {
            String result = orchestrator.searchJobs(userId(toolContext), keyword.trim(), city.trim());
            log.info("[观察] search_jobs 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] search_jobs 失败 | 原因: {} | 建议: 检查招聘平台API连接", e.getMessage(), e);
            return error("搜索岗位失败: " + e.getMessage());
        }
    }

    @Tool(name = "apply_jobs",
            description = "立即投递最近一次搜索结果中的岗位。仅在用户明确说“投1号”“投1和3号”或“全部投递”时调用。")
    public String applyJobs(
            @ToolParam(description = "用户选择岗位的原始消息，例如“投1号”“投1和3号”“全部投递”") String user_message,
            ToolContext toolContext) {

        if (user_message == null || user_message.trim().isEmpty()) {
            return error("请提供求职需求描述");
        }
        log.info("[行动] LLM调用工具: apply_jobs");
        log.info("  用户原始需求: \"{}\"", user_message.length() > 100
                ? user_message.substring(0, 100) + "..." : user_message);

        try {
            return orchestrator.applyJobs(userId(toolContext), user_message.trim());
        } catch (Exception e) {
            log.error("[异常] 投递失败 | 原因: {}", e.getMessage(), e);
            return error("投递失败: " + e.getMessage());
        }
    }

    @Tool(name = "get_application_progress", description = "查询求职投递进度。用户问投了多少家、求职进度怎么样时调用。")
    public String getApplicationProgress(ToolContext toolContext) {

        log.info("[行动] LLM调用工具: get_application_progress");

        try {
            String result = orchestrator.getApplicationProgress(userId(toolContext));
            log.info("[观察] get_application_progress 返回: {} 字符", result.length());
            return result;
        } catch (Exception e) {
            log.error("[异常] 查询投递进度失败 | 原因: {}", e.getMessage(), e);
            return error("查询失败: " + e.getMessage());
        }
    }

    private String userId(ToolContext toolContext) {
        Object value = toolContext.getContext().get("userId");
        if (!(value instanceof String userId) || userId.isBlank()) {
            throw new IllegalStateException("缺少当前微信用户信息");
        }
        return userId.trim();
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("success", false, "message", message));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"" + message + "\"}";
        }
    }
}
