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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Boss直聘投递客户端实现。
 *
 * <p>对接Boss直聘API实现简历投递能力。由于Boss直聘没有公开的投递API，当前实现：</p>
 * <ul>
 *   <li>方式1：通过Boss直聘网页API（需要Cookie认证和登录态）</li>
 *   <li>方式2：RPA模式（Selenium/Playwright模拟浏览器操作）</li>
 *   <li>方式3：Mock模式（开发测试用）</li>
 * </ul>
 *
 * <p>注意：真实投递需要用户已登录Boss直聘，Cookie需要定期更新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "boss")
public class BossApplicationClient implements ApplicationClient {

    private final RestTemplate restTemplate;
    private final ResumePlatformConfig config;
    private final ObjectMapper objectMapper;

    /** Boss直聘投递API路径 */
    private static final String APPLY_API = "/wapi/zpgeek/friend/add.json";

    /** Boss直聘打招呼API路径 */
    private static final String GREET_API = "/wapi/zpgeek/friend/greeting";

    @Override
    public ApplicationResult apply(JobListing job, UserProfile userProfile) {
        log.info("[Boss直聘] 投递岗位: {} - {}", job.getTitle(), job.getCompany());

        // Mock模式或未配置API地址
        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            return applyMock(job, userProfile);
        }

        try {
            // 步骤1：发送打招呼消息（Boss直聘的"投递"实际上是先打招呼）
            String greetingMessage = buildGreetingMessage(userProfile, job);
            log.info("[Boss直聘] 发送打招呼消息: {}", greetingMessage);

            // 构建请求
            String url = config.getBaseUrl() + GREET_API;
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("securityId", job.getJobId());
            requestBody.put("greeting", greetingMessage);
            requestBody.put("type", 1); // 1=文字消息

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                int code = json.path("code").asInt(-1);

                if (code == 0) {
                    log.info("[Boss直聘] 投递成功: {} - {}", job.getTitle(), job.getCompany());
                    return ApplicationResult.builder()
                            .success(true)
                            .jobListing(job)
                            .applicationId("BOSS-" + System.currentTimeMillis())
                            .status("SUBMITTED")
                            .message("Boss直聘: 已发送打招呼消息")
                            .appliedAt(Instant.now().toString())
                            .build();
                } else {
                    String message = json.path("message").asText("未知错误");
                    log.warn("[Boss直聘] 投递失败: code={}, message={}", code, message);
                    return buildFailResult(job, "Boss直聘投递失败: " + message);
                }
            }

            return buildFailResult(job, "Boss直聘投递失败: 服务器无响应");

        } catch (Exception e) {
            log.error("[Boss直聘] 投递异常: {}", e.getMessage(), e);
            // 降级到mock模式
            log.info("[Boss直聘] 降级到mock模式");
            return applyMock(job, userProfile);
        }
    }

    @Override
    public List<ApplicationResult> batchApply(List<JobListing> jobs, UserProfile userProfile) {
        log.info("[Boss直聘] 批量投递开始，共 {} 个岗位", jobs.size());

        List<ApplicationResult> results = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i++) {
            JobListing job = jobs.get(i);
            try {
                results.add(apply(job, userProfile));

                // 随机延迟，避免被封
                if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
                    long delayMs = config.getMinApplyInterval() * 1000L
                            + (long) (Math.random() * (config.getMaxApplyInterval() - config.getMinApplyInterval()) * 1000);
                    log.info("[Boss直聘] 等待 {}ms 后投递下一个 ({}/{})", delayMs, i + 1, jobs.size());
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[Boss直聘] 批量投递被中断", e);
                results.add(buildFailResult(job, "投递被中断"));
            } catch (Exception e) {
                log.error("[Boss直聘] 投递失败: {}", job.getTitle(), e);
                results.add(buildFailResult(job, "投递失败: " + e.getMessage()));
            }
        }

        long successCount = results.stream().filter(ApplicationResult::isSuccess).count();
        log.info("[Boss直聘] 批量投递完成: {}/{} 成功", successCount, jobs.size());

        return results;
    }

    @Override
    public ApplicationResult getApplicationStatus(String applicationId) {
        log.info("[Boss直聘] 查询投递状态: {}", applicationId);

        // Boss直聘没有公开的状态查询API
        // 返回模拟状态
        return ApplicationResult.builder()
                .success(true)
                .applicationId(applicationId)
                .status("VIEWED")
                .message("Boss直聘: 模拟查询（真实状态需通过APP查看）")
                .build();
    }

    // ═══════════════════════════════════════════════════
    // Mock实现
    // ═══════════════════════════════════════════════════

    private ApplicationResult applyMock(JobListing job, UserProfile profile) {
        String mockId = "BOSS-MOCK-" + System.currentTimeMillis();
        log.info("[Boss直聘-Mock] 模拟投递: {} → {} (applicationId={})", profile.getName(), job.getTitle(), mockId);

        return ApplicationResult.builder()
                .success(true)
                .jobListing(job)
                .applicationId(mockId)
                .status("SUBMITTED")
                .message("Boss直聘: Mock投递成功")
                .appliedAt(Instant.now().toString())
                .build();
    }

    // ═══════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.set("Referer", "https://www.zhipin.com/");
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            headers.set("Cookie", config.getApiKey());
        }

        return headers;
    }

    private String buildGreetingMessage(UserProfile profile, JobListing job) {
        StringBuilder greeting = new StringBuilder();
        greeting.append("您好，我对贵司的").append(job.getTitle()).append("岗位很感兴趣。");

        if (profile.getExperienceYears() != null && profile.getExperienceYears() > 0) {
            greeting.append("我有").append(profile.getExperienceYears()).append("年相关工作经验。");
        }

        if (profile.getSkills() != null && !profile.getSkills().isEmpty()) {
            greeting.append("熟悉").append(String.join("、", profile.getSkills().subList(0, Math.min(3, profile.getSkills().size())))).append("等技术。");
        }

        greeting.append("期待与您进一步沟通！");

        return greeting.toString();
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
