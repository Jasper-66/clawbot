package com.example.clawbot.liepin.tool;

import com.example.clawbot.liepin.service.LiepinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 猎聘求职工具 — LLM Function Calling 工具。
 *
 * <p>提供职位搜索、简历投递、智能推荐、投递状态查询四大功能，
 * 用户在微信对话中通过自然语言触发对应操作。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiepinTool {

    private final LiepinService liepinService;

    private static final String NAME = "liepin";

    public String getToolName() { return NAME; }

    /**
     * 工具定义（OpenAI Function Calling 格式）。
     */
    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", "猎聘求职助手，支持职位搜索、简历投递、智能推荐、投递状态查询。当用户提到找工作、推荐岗位、投简历、看职位、求职、招聘、岗位推荐等意图时，必须调用此工具。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "action", Map.of(
                                                "type", "string",
                                                "description", "操作类型：search（搜索职位）、apply（投递简历）、recommend（智能推荐）、status（查看投递状态）",
                                                "enum", List.of("search", "apply", "recommend", "status")
                                        ),
                                        "keyword", Map.of(
                                                "type", "string",
                                                "description", "职位关键词，如「Java开发」「产品经理」。search 时使用。"
                                        ),
                                        "city", Map.of(
                                                "type", "string",
                                                "description", "城市名称，如「北京」「上海」。search 时使用。"
                                        ),
                                        "job_id", Map.of(
                                                "type", "string",
                                                "description", "职位ID。apply 时使用。"
                                        )
                                ),
                                "required", List.of("action")
                        )
                )
        );
    }

    @Tool(name = "liepin", description = "猎聘求职助手，支持职位搜索、简历投递、智能推荐、投递状态查询。当用户提到找工作、推荐岗位、投简历、看职位、求职、招聘、岗位推荐等意图时，必须调用此工具。")
    public String liepin(
            @ToolParam(required = true, description = "操作类型：search/apply/recommend/status") String action,
            @ToolParam(required = false, description = "职位关键词，search 时使用") String keyword,
            @ToolParam(required = false, description = "城市名称，search 时使用") String city,
            @ToolParam(required = false, description = "职位ID，apply 时使用") String jobId) {

        log.info("猎聘工具调用: action={}, keyword={}, city={}, jobId={}", action, keyword, city, jobId);

        return switch (action) {
            case "search" -> {
                if (keyword == null || keyword.isBlank()) {
                    yield "⚠️ 请告诉我你想搜索什么职位，如「搜索北京Java开发」";
                }
                if (city == null || city.isBlank()) {
                    city = "全国";
                }
                yield liepinService.searchJobs(keyword, city);
            }
            case "apply" -> {
                if (jobId == null || jobId.isBlank()) {
                    yield "⚠️ 请告诉我要投递哪个职位的 ID";
                }
                yield liepinService.applyJob(jobId);
            }
            case "recommend" -> liepinService.recommendJobs();
            case "status" -> liepinService.checkApplications();
            default -> "⚠️ 不支持的操作：" + action + "，可选：search/apply/recommend/status";
        };
    }

    /**
     * 获取工具定义（兼容旧版 Function Calling）。
     */
    public String execute(String functionName, String argumentsJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode args = mapper.readTree(argumentsJson);
            return liepin(
                    args.path("action").asText(""),
                    args.path("keyword").asText(null),
                    args.path("city").asText(null),
                    args.path("job_id").asText(null)
            );
        } catch (Exception e) {
            return "⚠️ 猎聘工具参数解析失败：" + e.getMessage();
        }
    }
}
