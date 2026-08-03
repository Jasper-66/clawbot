package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.LiepinMcpApiClient;
import com.example.clawbot.resume.config.ResumePlatformConfig;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * 猎聘（Liepin）投递客户端实现。
 *
 * <p>对接猎聘 PC 端投递 API 实现简历投递能力。</p>
 * <p>投递流程：before-apply → apply-data → apply-for-pc</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "liepin")
public class LiepinApplicationClient implements ApplicationClient {

    private final RestTemplate restTemplate;
    private final ResumePlatformConfig config;
    private final ObjectMapper objectMapper;
    private final LiepinMcpApiClient mcpClient;

    /** 猎聘投递前置检查 API */
    private static final String BEFORE_APPLY_API = "/api/com.liepin.cbff.apply.before-apply";
    /** 猎聘投递数据 API */
    private static final String APPLY_DATA_API = "/api/com.liepin.capply.apply.apply-data";
    /** 猎聘 PC 端投递 API（主投递接口） */
    private static final String APPLY_FOR_PC_API = "/api/com.liepin.capply.platform.apply-for-pc";

    @Override
    public ApplicationResult apply(JobListing job, UserProfile userProfile) {
        log.info("[猎聘] 投递岗位: {} - {} (jobId={}, jobKind={})",
                job.getTitle(), job.getCompany(), job.getJobId(), job.getJobKind());

        // 优先使用 MCP API（官方 API，Token 认证）
        if (mcpClient.isAvailable()) {
            log.info("[猎聘] 优先使用 MCP API 投递");
            ApplicationResult mcpResult = mcpClient.applyJob(job);
            if (mcpResult != null) {
                return mcpResult;
            }
            log.warn("[猎聘] MCP API 投递失败，回退到 Cookie 方式");
        }

        if (config.getLiepinCookie() == null || config.getLiepinCookie().isEmpty()
                || config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            log.info("[猎聘] Cookie 未配置或 baseUrl 为空，使用 Mock 模式");
            return applyMock(job, userProfile);
        }

        try {
            // Step 1: 前置检查 before-apply
            if (!callBeforeApply(job)) {
                log.warn("[猎聘] 前置检查失败，跳过投递: {}", job.getJobId());
            }

            // Step 2: 获取投递数据 apply-data
            callApplyData(job);

            // Step 3: 执行投递 apply-for-pc
            ApplicationResult result = callApplyForPc(job);
            // 如果返回null表示认证失败，降级到Mock
            if (result == null) {
                log.warn("[猎聘] 投递API认证失败（Cookie/Token过期），降级到Mock模式");
                return applyMock(job, userProfile);
            }
            return result;

        } catch (Exception e) {
            log.error("[猎聘] 投递异常: {}", e.getMessage(), e);
            log.info("[猎聘] 降级到 Mock 模式");
            return applyMock(job, userProfile);
        }
    }

    /**
     * 调用前置检查 API。
     */
    private boolean callBeforeApply(JobListing job) {
        try {
            String url = config.getBaseUrl() + BEFORE_APPLY_API;
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", job.getJobId());
            body.put("jobKind", job.getJobKind() != null ? job.getJobKind() : "6");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                int flag = json.path("flag").asInt(0);
                log.info("[猎聘] before-apply: flag={}, jobId={}", flag, job.getJobId());
                return flag == 1;
            }
        } catch (Exception e) {
            log.warn("[猎聘] before-apply 调用失败: {}", e.getMessage());
        }
        return true; // 前置检查失败不阻止投递
    }

    /**
     * 调用投递数据 API。
     */
    private void callApplyData(JobListing job) {
        try {
            String url = config.getBaseUrl() + APPLY_DATA_API;
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobId", job.getJobId());
            body.put("jobKind", job.getJobKind() != null ? job.getJobKind() : "6");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("[猎聘] apply-data 调用完成: jobId={}", job.getJobId());
        } catch (Exception e) {
            log.warn("[猎聘] apply-data 调用失败: {}", e.getMessage());
        }
    }

    /**
     * 调用主投递 API（apply-for-pc）。
     */
    private ApplicationResult callApplyForPc(JobListing job) {
        try {
            String url = config.getBaseUrl() + APPLY_FOR_PC_API;
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("jobId", job.getJobId());
            requestBody.put("jobKind", job.getJobKind() != null ? job.getJobKind() : "6");
            requestBody.put("deliveryResumeType", "1"); // 1=在线简历
            requestBody.put("resumeId", ""); // 空字符串使用默认简历

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("[猎聘] apply-for-pc 请求: url={}, jobId={}, jobKind={}",
                    url, job.getJobId(), job.getJobKind());

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // 检查认证错误（Cookie/Token过期）
                if (isAuthError(response.getBody())) {
                    log.warn("[猎聘] apply-for-pc 认证失败: {}", response.getBody());
                    return null;
                }
                JsonNode json = objectMapper.readTree(response.getBody());
                boolean success = json.path("flag").asInt(0) == 1
                        || json.path("code").asInt(-1) == 1
                        || json.path("code").asInt(-1) == 200;
                String message = json.path("msg").asText(json.path("message").asText(""));

                // 检查是否重复投递
                if (!success && message.contains("已经投递")) {
                    log.info("[猎聘] 岗位已投递过: {} - {}", job.getTitle(), job.getCompany());
                    return ApplicationResult.builder()
                            .success(true)
                            .realSuccess(true)
                            .deliveryMethod("COOKIE_API")
                            .jobListing(job)
                            .applicationId("LIEPIN-EXIST-" + job.getJobId())
                            .status("ALREADY_APPLIED")
                            .message("猎聘: 该岗位已投递过")
                            .appliedAt(Instant.now().toString())
                            .build();
                }

                if (success) {
                    log.info("[猎聘] 投递成功: {} - {}", job.getTitle(), job.getCompany());
                    return ApplicationResult.builder()
                            .success(true)
                            .realSuccess(true)
                            .deliveryMethod("COOKIE_API")
                            .jobListing(job)
                            .applicationId("LIEPIN-" + System.currentTimeMillis())
                            .status("SUBMITTED")
                            .message("猎聘: 投递成功")
                            .appliedAt(Instant.now().toString())
                            .build();
                } else {
                    log.warn("[猎聘] 投递失败: {}", message);
                    return buildFailResult(job, "猎聘投递失败: " + (message.isEmpty() ? "未知错误" : message));
                }
            }

            return buildFailResult(job, "猎聘投递失败: 服务器无响应");
        } catch (Exception e) {
            log.error("[猎聘] apply-for-pc 调用异常: {}", e.getMessage(), e);
            return buildFailResult(job, "猎聘投递失败: " + e.getMessage());
        }
    }

    @Override
    public List<ApplicationResult> batchApply(List<JobListing> jobs, UserProfile userProfile) {
        log.info("[猎聘] 批量投递开始，共 {} 个岗位", jobs.size());

        List<ApplicationResult> results = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i++) {
            JobListing job = jobs.get(i);
            try {
                results.add(apply(job, userProfile));

                // 随机延迟（防封）
                if (config.getLiepinCookie() != null && !config.getLiepinCookie().isEmpty()
                        && config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
                    long delayMs = config.getMinApplyInterval() * 1000L
                            + (long) (Math.random() * (config.getMaxApplyInterval() - config.getMinApplyInterval()) * 1000);
                    log.info("[猎聘] 等待 {}ms 后投递下一个 ({}/{})", delayMs, i + 1, jobs.size());
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(buildFailResult(job, "投递被中断"));
            } catch (Exception e) {
                log.error("[猎聘] 投递失败: {}", job.getTitle(), e);
                results.add(buildFailResult(job, "投递失败: " + e.getMessage()));
            }
        }

        long successCount = results.stream().filter(ApplicationResult::isSuccess).count();
        log.info("[猎聘] 批量投递完成: {}/{} 成功", successCount, jobs.size());
        return results;
    }

    @Override
    public ApplicationResult getApplicationStatus(String applicationId) {
        log.info("[猎聘] 查询投递状态: {}", applicationId);
        return ApplicationResult.builder()
                .success(true)
                .applicationId(applicationId)
                .status("SUBMITTED")
                .message("猎聘: 状态查询请在猎聘APP查看")
                .build();
    }

    // ═══════════════════════════════════════════════════
    // Mock 实现
    // ═══════════════════════════════════════════════════

    private ApplicationResult applyMock(JobListing job, UserProfile profile) {
        String mockId = "LIEPIN-MOCK-" + System.currentTimeMillis();
        log.warn("[猎聘-Mock] ⚠️ 使用模拟投递（真实投递失败或未配置）: {} → {} (applicationId={})",
                profile.getName(), job.getTitle(), mockId);

        return ApplicationResult.builder()
                .success(false)  // Mock模式不算真实成功
                .realSuccess(false)
                .deliveryMethod("MOCK")
                .jobListing(job)
                .applicationId(mockId)
                .status("MOCK")
                .message("⚠️ 真实投递未生效（Cookie/Token未配置或已过期），当前为模拟投递。请在 application.properties 中配置有效的 resume.platform.liepin-cookie 或 resume.platform.liepin-token")
                .appliedAt(Instant.now().toString())
                .build();
    }

    // ═══════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Edg/150.0.0.0");
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7");
        headers.set("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.set("Origin", "https://www.liepin.com");
        headers.set("Referer", "https://www.liepin.com/");
        headers.set("Connection", "keep-alive");
        headers.set("X-Client-Type", "web");
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set("X-Fscp-Version", "1.1");
        headers.set("X-Fscp-Fe-Version", "");

        // 使用 Cookie 认证（优先）
        if (config.getLiepinCookie() != null && !config.getLiepinCookie().isEmpty()) {
            headers.set("Cookie", config.getLiepinCookie());
            // 从 Cookie 中提取 XSRF-TOKEN 并放到请求头
            String xsrfToken = extractXsrfToken(config.getLiepinCookie());
            if (xsrfToken != null && !xsrfToken.isEmpty()) {
                headers.set("X-XSRF-TOKEN", xsrfToken);
            }
        }
        // 同时带上 Token（兼容）
        if (config.getLiepinToken() != null && !config.getLiepinToken().isEmpty()) {
            headers.set("X-Token", config.getLiepinToken());
        }
        return headers;
    }

    /**
     * 从 Cookie 字符串中提取 XSRF-TOKEN 的值。
     */
    private String extractXsrfToken(String cookie) {
        if (cookie == null || cookie.isEmpty()) return null;
        for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith("XSRF-TOKEN=")) {
                return part.substring("XSRF-TOKEN=".length());
            }
        }
        return null;
    }

    /**
     * 检查响应是否为认证错误（Cookie/Token过期）。
     * code=-1400 或 msg包含"400"/"出错了" 表示认证失败。
     */
    private boolean isAuthError(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            int flag = json.path("flag").asInt(1);
            String code = json.path("code").asText("");
            String msg = json.path("msg").asText("");
            return flag == 0 && ("-1400".equals(code) || msg.contains("400") || msg.contains("出错了"));
        } catch (Exception e) {
            return false;
        }
    }

    private ApplicationResult buildFailResult(JobListing job, String message) {
        return ApplicationResult.builder()
                .success(false)
                .jobListing(job)
                .message(message)
                .appliedAt(Instant.now().toString())
                .build();
    }
}
