package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.config.ResumePlatformConfig;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// ── 成员5: 投递执行实现 ──
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationClientImpl implements ApplicationClient {

    private final RestTemplate restTemplate;
    private final ResumePlatformConfig config;
    private final ObjectMapper objectMapper;

    @Override
    public ApplicationResult apply(JobListing job, UserProfile userProfile) {
        String provider = config.getProvider();
        log.info("投递岗位: {} - {} (平台={})", job.getTitle(), job.getCompany(), provider);

        if ("tencent".equals(provider)) {
            return applyTencent(job, userProfile);
        }

        // 默认 mock 模式
        return applyMock(job, userProfile);
    }

    @Override
    public List<ApplicationResult> batchApply(List<JobListing> jobs, UserProfile userProfile) {
        String provider = config.getProvider();
        log.info("批量投递开始，共 {} 个岗位 (平台={})", jobs.size(), provider);
        List<ApplicationResult> results = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i++) {
            JobListing job = jobs.get(i);
            try {
                results.add(apply(job, userProfile));

                // 真实模式下随机延迟，避免被平台封禁
                if (!"mock".equals(provider)) {
                    long delayMs = config.getMinApplyInterval() * 1000L
                            + (long) (Math.random() * (config.getMaxApplyInterval() - config.getMinApplyInterval()) * 1000);
                    log.info("等待 {}ms 后投递下一个 ({}/{})...", delayMs, i + 1, jobs.size());
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("批量投递被中断", e);
                results.add(buildFailResult(job, "投递被中断: " + e.getMessage()));
            } catch (Exception e) {
                log.error("投递失败: {}", job.getTitle(), e);
                results.add(buildFailResult(job, "投递失败: " + e.getMessage()));
            }
        }

        long successCount = results.stream().filter(ApplicationResult::isSuccess).count();
        log.info("批量投递完成: {}/{} 成功", successCount, jobs.size());
        return results;
    }

    @Override
    public ApplicationResult getApplicationStatus(String applicationId) {
        String provider = config.getProvider();
        log.info("查询投递状态: {} (平台={})", applicationId, provider);

        if ("tencent".equals(provider)) {
            return getStatusTencent(applicationId);
        }

        // 默认 mock 模式
        return getStatusMock(applicationId);
    }

    // ═══════════════════════════════════════
    // Mock 模式 — 开发阶段用，返回模拟数据
    // ═══════════════════════════════════════

    private ApplicationResult applyMock(JobListing job, UserProfile profile) {
        String mockId = "MOCK-" + System.currentTimeMillis();
        log.info("[Mock] 模拟投递: {} → {} (applicationId={})", profile.getName(), job.getTitle(), mockId);

        return ApplicationResult.builder()
                .success(true)
                .jobListing(job)
                .applicationId(mockId)
                .status("SUBMITTED")
                .message("Mock投递成功")
                .appliedAt(Instant.now().toString())
                .build();
    }

    private ApplicationResult getStatusMock(String applicationId) {
        log.info("[Mock] 查询投递状态: {} → VIEWED", applicationId);
        return ApplicationResult.builder()
                .success(true)
                .applicationId(applicationId)
                .status("VIEWED")
                .message("Mock: 简历已被查看")
                .build();
    }

    // ═══════════════════════════════════════
    // 腾讯招聘模式
    // ═══════════════════════════════════════
    // 腾讯招聘没有公开的投递API，投递需要登录后在网页上操作
    // 当前阶段：返回模拟结果，预留真实API接口
    // 后续方案：Selenium/Playwright RPA 模拟浏览器投递

    private ApplicationResult applyTencent(JobListing job, UserProfile profile) {
        log.info("[腾讯] 投递岗位: {} - {}", job.getTitle(), job.getCompany());

        // 未配置API地址时，返回模拟结果
        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            log.warn("[腾讯] 未配置API地址，返回模拟结果");
            return ApplicationResult.builder()
                    .success(true)
                    .jobListing(job)
                    .applicationId("TENCENT-" + System.currentTimeMillis())
                    .status("SUBMITTED")
                    .message("腾讯招聘: 模拟投递成功（腾讯无公开投递API，需RPA实现）")
                    .appliedAt(Instant.now().toString())
                    .build();
        }

        // 真实API调用（预留）
        try {
            String url = config.getBaseUrl() + "/api/applications";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + config.getApiKey());
            headers.setContentType(MediaType.APPLICATION_JSON);

            String body = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("jobId", job.getJobId());
                put("userId", profile.getUserId());
                put("resumeData", new java.util.LinkedHashMap<>() {{
                    put("name", profile.getName());
                    put("phone", profile.getPhone());
                    put("email", profile.getEmail());
                    put("skills", profile.getSkills());
                    put("experience", profile.getWorkHistory());
                }});
            }});

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);

            JsonNode json = objectMapper.readTree(response.getBody());
            return ApplicationResult.builder()
                    .success(json.path("success").asBoolean(true))
                    .jobListing(job)
                    .applicationId(json.path("applicationId").asText())
                    .status(json.path("status").asText("SUBMITTED"))
                    .message(json.path("message").asText("投递成功"))
                    .appliedAt(Instant.now().toString())
                    .build();

        } catch (Exception e) {
            log.error("[腾讯] 投递失败: {}", e.getMessage(), e);
            return buildFailResult(job, "腾讯投递失败: " + e.getMessage());
        }
    }

    private ApplicationResult getStatusTencent(String applicationId) {
        log.info("[腾讯] 查询投递状态: {}", applicationId);

        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            return ApplicationResult.builder()
                    .success(true)
                    .applicationId(applicationId)
                    .status("VIEWED")
                    .message("腾讯招聘: 模拟查询（未配置真实API）")
                    .build();
        }

        // 真实API调用（预留）
        try {
            String url = config.getBaseUrl() + "/api/applications/" + applicationId;

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + config.getApiKey());

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);

            JsonNode json = objectMapper.readTree(response.getBody());
            return ApplicationResult.builder()
                    .success(true)
                    .applicationId(applicationId)
                    .status(json.path("status").asText("UNKNOWN"))
                    .message(json.path("message").asText(""))
                    .build();

        } catch (Exception e) {
            log.error("[腾讯] 查询状态失败: {}", e.getMessage(), e);
            return ApplicationResult.builder()
                    .success(false)
                    .applicationId(applicationId)
                    .message("腾讯查询失败: " + e.getMessage())
                    .build();
        }
    }

    // ═══════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════

    private ApplicationResult buildFailResult(JobListing job, String message) {
        return ApplicationResult.builder()
                .success(false)
                .jobListing(job)
                .message(message)
                .appliedAt(Instant.now().toString())
                .build();
    }
}
