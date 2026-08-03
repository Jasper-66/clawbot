package com.example.clawbot.resume.client;

import com.example.clawbot.resume.config.ResumePlatformConfig;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * 猎聘官方 MCP API 客户端。
 *
 * <p>使用 JSON-RPC 2.0 协议，通过 x-user-token 认证。</p>
 * <p>API 端点: https://open-agent.liepin.com/mcp/user</p>
 *
 * <p>支持的工具：</p>
 * <ul>
 *   <li>user-search-job - 搜索职位</li>
 *   <li>user-apply-job - 投递职位</li>
 *   <li>my-resume - 获取简历</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiepinMcpApiClient {

    private final RestTemplate restTemplate;
    private final ResumePlatformConfig config;
    private final ObjectMapper objectMapper;

    /** 猎聘 MCP API 端点 */
    private static final String MCP_API_URL = "https://open-agent.liepin.com/mcp/user";

    /**
     * 检查 MCP API 是否可用（Token 是否配置）。
     */
    public boolean isAvailable() {
        return config.getLiepinToken() != null && !config.getLiepinToken().trim().isEmpty();
    }

    /**
     * 搜索职位。
     *
     * @param keyword 职位关键词
     * @param city 城市
     * @return 岗位列表
     */
    public List<JobListing> searchJobs(String keyword, String city) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        log.info("[猎聘-MCP] 搜索岗位: keyword={}, city={}", keyword, city);

        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("jobName", keyword);
            if (city != null && !city.isBlank()) {
                arguments.put("address", city);
            }

            JsonNode result = callTool("user-search-job", arguments);
            if (result == null) {
                return Collections.emptyList();
            }

            return parseSearchResult(result);

        } catch (Exception e) {
            log.error("[猎聘-MCP] 搜索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 投递职位。
     *
     * @param job 岗位信息
     * @return 投递结果
     */
    public ApplicationResult applyJob(JobListing job) {
        if (!isAvailable()) {
            return null;
        }

        log.info("[猎聘-MCP] 投递岗位: {} - {} (jobId={})",
                job.getTitle(), job.getCompany(), job.getJobId());

        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("jobId", job.getJobId());
            if (job.getJobKind() != null && !job.getJobKind().isEmpty()) {
                arguments.put("jobKind", job.getJobKind());
            }

            JsonNode result = callTool("user-apply-job", arguments);
            if (result == null) {
                return null;
            }

            return parseApplyResult(result, job);

        } catch (Exception e) {
            log.error("[猎聘-MCP] 投递异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取用户简历。
     *
     * @return 简历内容（JSON 字符串）
     */
    public String getResume() {
        if (!isAvailable()) {
            return null;
        }

        log.info("[猎聘-MCP] 获取简历");

        try {
            JsonNode result = callTool("my-resume", Collections.emptyMap());
            if (result == null) {
                return null;
            }

            // content 格式: [{type: "text", text: "..."}]
            String contentText = extractContentText(result);
            if (!contentText.isEmpty()) {
                return contentText;
            }
            return result.toString();

        } catch (Exception e) {
            log.error("[猎聘-MCP] 获取简历异常: {}", e.getMessage(), e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════
    // 底层 JSON-RPC 调用
    // ═══════════════════════════════════════════════════

    /**
     * 调用 MCP 工具。
     *
     * @param toolName 工具名
     * @param arguments 参数
     * @return 结果（result.content 部分）
     */
    private JsonNode callTool(String toolName, Map<String, Object> arguments) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json, text/event-stream");
            headers.set("x-user-token", config.getLiepinToken().trim());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("method", "tools/call");
            body.put("id", System.currentTimeMillis());
            body.put("params", params);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            log.debug("[猎聘-MCP] 请求: tool={}, args={}", toolName, arguments);

            ResponseEntity<String> response = restTemplate.exchange(
                    MCP_API_URL, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("[猎聘-MCP] 请求失败: status={}", response.getStatusCode());
                return null;
            }

            log.debug("[猎聘-MCP] 响应 (前500字): {}", response.getBody().length() > 500
                    ? response.getBody().substring(0, 500) : response.getBody());

            // 解析 JSON-RPC 响应
            String rawBody = response.getBody();
            log.info("[猎聘-MCP] 原始响应 (前800字): {}", rawBody.length() > 800
                    ? rawBody.substring(0, 800) : rawBody);

            JsonNode root = objectMapper.readTree(rawBody);

            // 检查是否有错误
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText("未知错误");
                log.warn("[猎聘-MCP] 工具返回错误: tool={}, error={}", toolName, errorMsg);
                return null;
            }

            // 返回 result 节点
            JsonNode result = root.path("result");
            log.info("[猎聘-MCP] result 节点: {}", result.toString().length() > 500
                    ? result.toString().substring(0, 500) : result.toString());
            return result;

        } catch (Exception e) {
            log.error("[猎聘-MCP] 调用工具异常: tool={}, error={}", toolName, e.getMessage(), e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════
    // 结果解析
    // ═══════════════════════════════════════════════════

    /**
     * 解析搜索结果。
     */
    private List<JobListing> parseSearchResult(JsonNode result) {
        List<JobListing> jobs = new ArrayList<>();

        try {
            // result.content 格式: [{type: "text", text: "..."}]
            String contentStr = extractContentText(result);
            if (contentStr.isEmpty()) {
                log.warn("[猎聘-MCP] 搜索结果 content 为空");
                return jobs;
            }

            JsonNode content = objectMapper.readTree(contentStr);

            // content 可能是数组或者包含 data 的对象
            // 实际格式: {"data": {"list": [...]}}
            JsonNode jobList = null;
            if (content.isArray()) {
                jobList = content;
            } else if (content.has("data")) {
                JsonNode data = content.path("data");
                if (data.isArray()) {
                    jobList = data;
                } else if (data.has("list")) {
                    jobList = data.path("list");
                } else if (data.has("jobCardList")) {
                    jobList = data.path("jobCardList");
                }
            } else if (content.has("list")) {
                jobList = content.path("list");
            }

            if (jobList == null || !jobList.isArray()) {
                log.warn("[猎聘-MCP] 未找到岗位列表，content 结构: {}", contentStr.substring(0, Math.min(200, contentStr.length())));
                return jobs;
            }

            for (JsonNode item : jobList) {
                try {
                    JobListing job = parseJobItem(item);
                    if (job != null && job.getJobId() != null && !job.getJobId().isEmpty()) {
                        jobs.add(job);
                    }
                } catch (Exception e) {
                    log.warn("[猎聘-MCP] 解析单条岗位失败: {}", e.getMessage());
                }
            }

            log.info("[猎聘-MCP] 搜索完成: 返回 {} 个岗位", jobs.size());

        } catch (Exception e) {
            log.error("[猎聘-MCP] 解析搜索结果异常: {}", e.getMessage(), e);
        }

        return jobs;
    }

    /**
     * 解析单条岗位信息。
     */
    private JobListing parseJobItem(JsonNode item) {
        // jobId 可能是数字或字符串
        String jobId = null;
        if (item.has("jobId")) {
            jobId = item.path("jobId").asText("");
        }
        if (jobId == null || jobId.isEmpty()) {
            jobId = getFirstText(item, "job_id", "id");
        }

        if (jobId == null || jobId.isEmpty()) {
            return null;
        }

        String title = getFirstText(item, "jobName", "title", "job_name", "jobTitle");
        String company = getFirstText(item, "company", "compName", "comp_name");
        String city = getFirstText(item, "location", "address", "city", "dq", "workCity");
        String salary = getFirstText(item, "salary", "salaryDesc");
        String jobKind = getFirstText(item, "jobType", "jobKind", "job_kind");
        String link = getFirstText(item, "jobDetailUrl", "link", "url", "detailUrl");

        return JobListing.builder()
                .jobId(jobId)
                .title(title != null ? title : "")
                .company(company != null ? company : "")
                .city(city != null ? city : "")
                .salary(salary != null ? salary : "")
                .jobKind(jobKind != null && !jobKind.isEmpty() ? jobKind : "6")
                .detailUrl(link)
                .industry(getFirstText(item, "industry", "compIndustry"))
                .experienceRequired(getFirstText(item, "workYears", "experience", "workYear"))
                .educationRequired(getFirstText(item, "education", "eduLevel"))
                .requiredSkills(Collections.emptyList())
                .applied(false)
                .build();
    }

    /**
     * 解析投递结果。
     */
    private ApplicationResult parseApplyResult(JsonNode result, JobListing job) {
        String contentStr = extractContentText(result);

        try {
            JsonNode content = objectMapper.readTree(contentStr);

            // ⚠️ 投递成功判断的核心逻辑：
            // errCode=0 只表示 API 调用成功，不代表投递成功！
            // 必须检查 data.result 或 message 中的具体内容。
            //
            // 常见响应格式：
            //   成功: {"data": {"result": "应聘成功"}, "errCode": 0}
            //   失败: {"data": {"result": "您的简历完整度不足65%..."}, "errCode": 0}
            //   已投: {"data": {"result": "已经投递过该职位"}, "errCode": 0}

            // 从 data.result 获取消息
            String message = getFirstText(content, "message", "msg");
            if (message == null || message.isEmpty()) {
                JsonNode dataNode = content.path("data");
                if (!dataNode.isMissingNode() && !dataNode.isNull()) {
                    message = getFirstText(dataNode, "result", "message", "msg");
                }
            }
            if (message == null || message.isEmpty()) {
                message = contentStr;
            }

            // 检查失败关键词（这些消息说明投递被拒绝了）
            boolean hasFailKeyword = message.contains("不足")
                    || message.contains("完善简历")
                    || message.contains("请您先")
                    || message.contains("无法")
                    || message.contains("不符合")
                    || message.contains("不满足")
                    || message.contains("已关闭")
                    || message.contains("已过期")
                    || message.contains("已满")
                    || message.contains("抱歉");

            // 检查成功关键词
            boolean hasSuccessKeyword = message.contains("投递成功")
                    || message.contains("申请成功")
                    || message.contains("应聘成功")
                    || message.contains("已成功")
                    || message.contains("成功投递");

            // 检查已投递关键词
            boolean hasAlreadyKeyword = message.contains("已经投递")
                    || message.contains("已投递过")
                    || message.contains("已申请过")
                    || message.contains("已经申请");

            log.info("[猎聘-MCP] 投递响应解析: hasFail={}, hasSuccess={}, hasAlready={}, message={}",
                    hasFailKeyword, hasSuccessKeyword, hasAlreadyKeyword, message);

            // 优先级：已投递 > 成功 > 失败
            if (hasAlreadyKeyword) {
                log.info("[猎聘-MCP] 岗位已投递过: {} - {}", job.getTitle(), job.getCompany());
                return ApplicationResult.builder()
                        .success(true)
                        .realSuccess(true)
                        .deliveryMethod("MCP_API")
                        .jobListing(job)
                        .applicationId("LIEPIN-EXIST-" + job.getJobId())
                        .status("ALREADY_APPLIED")
                        .message("猎聘: 该岗位已投递过")
                        .appliedAt(Instant.now().toString())
                        .build();
            }

            if (hasSuccessKeyword) {
                log.info("[猎聘-MCP] 投递成功: {} - {}", job.getTitle(), job.getCompany());
                return ApplicationResult.builder()
                        .success(true)
                        .realSuccess(true)
                        .deliveryMethod("MCP_API")
                        .jobListing(job)
                        .applicationId("LIEPIN-" + System.currentTimeMillis())
                        .status("SUBMITTED")
                        .message("猎聘: 投递成功")
                        .appliedAt(Instant.now().toString())
                        .build();
            }

            // 失败（包括有失败关键词，或者没有明确成功/已投递关键词）
            log.warn("[猎聘-MCP] 投递失败: {}", message);
            return ApplicationResult.builder()
                    .success(false)
                    .realSuccess(false)
                    .deliveryMethod("MCP_API")
                    .jobListing(job)
                    .message("猎聘投递失败: " + message)
                    .appliedAt(Instant.now().toString())
                    .build();

        } catch (Exception e) {
            log.warn("[猎聘-MCP] 解析投递结果失败，使用原始内容: {}", contentStr);

            // 只有非常明确的成功关键词才认为成功
            boolean isDefinitelySuccess = (contentStr.contains("投递成功") || contentStr.contains("申请成功")
                    || contentStr.contains("应聘成功") || contentStr.contains("已经投递")
                    || contentStr.contains("已投递过"));

            if (isDefinitelySuccess) {
                return ApplicationResult.builder()
                        .success(true)
                        .realSuccess(true)
                        .deliveryMethod("MCP_API")
                        .jobListing(job)
                        .applicationId("LIEPIN-" + System.currentTimeMillis())
                        .status("SUBMITTED")
                        .message("猎聘: 投递成功")
                        .appliedAt(Instant.now().toString())
                        .build();
            }

            return ApplicationResult.builder()
                    .success(false)
                    .realSuccess(false)
                    .deliveryMethod("MCP_API")
                    .jobListing(job)
                    .message("猎聘投递失败: " + (contentStr.isEmpty() ? "未知错误" : contentStr.substring(0, Math.min(200, contentStr.length()))))
                    .appliedAt(Instant.now().toString())
                    .build();
        }
    }

    /**
     * 从 result 节点中提取 content 文本。
     * content 格式: [{type: "text", text: "..."}]
     */
    private String extractContentText(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray() && content.size() > 0) {
            JsonNode firstItem = content.get(0);
            return firstItem.path("text").asText("");
        }
        return content.asText("");
    }

    /**
     * 从多个候选字段中获取第一个非空值。
     */
    private String getFirstText(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            if (node.has(field) && !node.path(field).isNull()) {
                String value = node.path(field).asText("");
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }
}
