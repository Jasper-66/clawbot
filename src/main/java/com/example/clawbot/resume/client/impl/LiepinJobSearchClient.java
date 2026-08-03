package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.client.LiepinMcpApiClient;
import com.example.clawbot.resume.config.ResumePlatformConfig;
import com.example.clawbot.resume.model.JobListing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 猎聘（Liepin）岗位搜索客户端实现。
 *
 * <p>对接猎聘开放平台 / Web API 实现岗位搜索能力。</p>
 *
 * <p>配置方式：在 application.properties 中设置</p>
 * <pre>
 * resume.platform.provider=liepin
 * resume.platform.base-url=https://api-c.liepin.com
 * resume.platform.liepin-token=用户的猎聘JWT Token
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "liepin")
public class LiepinJobSearchClient implements JobSearchClient {

    @Autowired
    private ResumePlatformConfig config;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LiepinMcpApiClient mcpClient;

    /** 猎聘岗位搜索 API（Web 端） */
    private static final String SEARCH_API = "/api/com.liepin.searchfront4c.pc-search-job";

    /** 猎聘职位详情 API */
    private static final String DETAIL_API = "/api/com.liepin.job.job4c.detail";

    @Override
    public List<JobListing> searchJobs(String keyword, String city, String experience, String salaryRange) {
        if (keyword == null || keyword.isBlank()) {
            log.warn("[猎聘] searchJobs 入参非法：keyword 为空");
            return Collections.emptyList();
        }

        log.info("[猎聘] 发起岗位搜索: keyword={}, city={}, experience={}, salary={}",
                keyword, city, experience, salaryRange);

        // 优先使用 MCP API（官方 API，Token 认证）
        if (mcpClient.isAvailable()) {
            log.info("[猎聘] 优先使用 MCP API 搜索");
            List<JobListing> mcpJobs = mcpClient.searchJobs(keyword, city);
            if (mcpJobs != null && !mcpJobs.isEmpty()) {
                log.info("[猎聘] MCP API 搜索成功: {} 个岗位", mcpJobs.size());
                return mcpJobs;
            }
            log.warn("[猎聘] MCP API 搜索结果为空，回退到 Cookie 方式");
        }

        // 如果 Cookie 未配置，返回模拟数据
        if ((config.getLiepinCookie() == null || config.getLiepinCookie().isEmpty())
                && (config.getLiepinToken() == null || config.getLiepinToken().isEmpty())) {
            log.info("[猎聘] Cookie 和 Token 均未配置，使用 Mock 模式");
            return searchJobsMock(keyword, city, experience, salaryRange);
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            log.info("[猎聘] baseUrl 为空，使用 Mock 模式");
            return searchJobsMock(keyword, city, experience, salaryRange);
        }

        try {
            String url = config.getBaseUrl() + SEARCH_API;
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构建真实的请求体（猎聘 PC 端格式）
            Map<String, Object> requestBody = buildSearchBodyReal(keyword, city, experience, salaryRange);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("[猎聘] 搜索请求: url={}, body={}", url, requestBody);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("[猎聘] 搜索请求失败: status={}", response.getStatusCode());
                return searchJobsMock(keyword, city, experience, salaryRange);
            }

            // 检查认证错误（code=-1400 表示Cookie/Token过期）
            if (isAuthError(response.getBody())) {
                log.warn("[猎聘] 搜索API认证失败（Cookie/Token过期），降级到Mock模式");
                return searchJobsMock(keyword, city, experience, salaryRange);
            }

            log.debug("[猎聘] 搜索响应 (前500字): {}", response.getBody().length() > 500
                    ? response.getBody().substring(0, 500) : response.getBody());
            return parseSearchResponse(response.getBody());

        } catch (Exception e) {
            log.error("[猎聘] 搜索异常: {}", e.getMessage(), e);
            log.info("[猎聘] 降级到 Mock 模式");
            return searchJobsMock(keyword, city, experience, salaryRange);
        }
    }

    @Override
    public JobListing getJobDetail(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        log.info("[猎聘] 获取岗位详情: jobId={}", jobId);

        if (config.getLiepinToken() == null || config.getBaseUrl() == null
                || config.getBaseUrl().isEmpty()) {
            return getJobDetailMock(jobId);
        }

        try {
            String url = config.getBaseUrl() + DETAIL_API;
            HttpHeaders headers = buildHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of("jobId", jobId);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseJobDetail(response.getBody(), jobId);
            }
        } catch (Exception e) {
            log.error("[猎聘] 获取详情异常: {}", e.getMessage(), e);
        }
        return getJobDetailMock(jobId);
    }

    @Override
    public boolean hasApplied(String jobId, String userId) {
        // 猎聘重复投递校验由本地 ApplicationTracker 完成
        log.debug("[猎聘] hasApplied 返回false（本地校验）: jobId={}", jobId);
        return false;
    }

    // ═══════════════════════════════════════════════════
    // Mock 实现
    // ═══════════════════════════════════════════════════

    private List<JobListing> searchJobsMock(String keyword, String city, String experience, String salaryRange) {
        log.info("[猎聘-Mock] 生成模拟岗位: keyword={}, city={}", keyword, city);

        List<JobListing> jobs = new ArrayList<>();
        Random random = new Random();
        int count = 5 + random.nextInt(6);
        String[] companies = {"猎聘网", "字节跳动", "腾讯", "阿里巴巴", "美团", "京东", "百度", "网易", "快手", "滴滴"};
        String[] salaries = {"15k-25k", "20k-35k", "25k-40k", "30k-50k", "35k-60k"};

        for (int i = 0; i < count; i++) {
            jobs.add(JobListing.builder()
                    .jobId("LIEPIN-MOCK-" + System.currentTimeMillis() + "-" + i)
                    .title(keyword + "工程师")
                    .company(companies[random.nextInt(companies.length)])
                    .city(city != null && !city.isEmpty() ? city : "北京")
                    .salary(salaries[random.nextInt(salaries.length)])
                    .experienceRequired(experience != null ? experience : "3-5年")
                    .educationRequired("本科")
                    .description("【岗位职责】\n1. 负责" + keyword + "系统开发\n2. 参与技术方案设计\n\n【任职要求】\n1. 本科及以上学历\n2. 3年以上相关经验")
                    .requiredSkills(List.of(keyword, "Java", "Spring Boot", "MySQL"))
                    .industry("互联网")
                    .detailUrl("https://www.liepin.com/job/MOCK" + i + ".shtml")
                    .publishDate("2026-07-" + (20 + random.nextInt(10)))
                    .applied(false)
                    .build());
        }
        log.info("[猎聘-Mock] 生成 {} 个模拟岗位", jobs.size());
        return jobs;
    }

    private JobListing getJobDetailMock(String jobId) {
        return JobListing.builder()
                .jobId(jobId)
                .title("Mock岗位详情")
                .company("猎聘-Mock公司")
                .city("北京")
                .salary("20k-35k")
                .experienceRequired("3-5年")
                .educationRequired("本科")
                .description("这是一个Mock岗位详情。\n\n【岗位职责】\n1. 负责系统开发\n2. 参与技术方案设计\n\n【任职要求】\n1. 本科及以上学历\n2. 3年以上开发经验")
                .requiredSkills(List.of("Java", "Spring Boot", "MySQL"))
                .industry("互联网")
                .build();
    }

    // ═══════════════════════════════════════════════════
    // 真实 API 辅助方法
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
     * 构建真实的猎聘 PC 端搜索请求体。
     * 参考：data.mainSearchPcConditionForm
     */
    private Map<String, Object> buildSearchBodyReal(String keyword, String city, String experience, String salaryRange) {
        // 城市代码映射（常用城市）
        Map<String, String> cityCodeMap = new HashMap<>();
        cityCodeMap.put("北京", "010");
        cityCodeMap.put("上海", "020");
        cityCodeMap.put("广州", "050020");
        cityCodeMap.put("深圳", "050090");
        cityCodeMap.put("杭州", "070020");
        cityCodeMap.put("成都", "280020");
        cityCodeMap.put("武汉", "170020");
        cityCodeMap.put("西安", "270020");
        cityCodeMap.put("南京", "060020");
        cityCodeMap.put("重庆", "040");
        cityCodeMap.put("天津", "030");
        cityCodeMap.put("苏州", "060060");
        cityCodeMap.put("长沙", "180020");
        cityCodeMap.put("郑州", "150020");
        cityCodeMap.put("青岛", "100060");
        cityCodeMap.put("合肥", "080020");
        cityCodeMap.put("佛山", "050040");
        cityCodeMap.put("东莞", "050050");
        cityCodeMap.put("沈阳", "110020");
        cityCodeMap.put("全国", "410");

        // 经验代码映射
        // 1=不限, 2=应届, 3=1-3年, 4=3-5年, 5=5-10年, 6=10年以上
        String workYearCode = "1";
        if (experience != null && !experience.isBlank()) {
            if (experience.contains("应届")) workYearCode = "2";
            else if (experience.contains("1") && experience.contains("3")) workYearCode = "3";
            else if (experience.contains("3") && experience.contains("5")) workYearCode = "4";
            else if (experience.contains("5") && experience.contains("10")) workYearCode = "5";
            else if (experience.contains("10")) workYearCode = "6";
        }

        // 城市代码
        String cityCode = "410"; // 默认全国
        if (city != null && !city.isBlank()) {
            cityCode = cityCodeMap.getOrDefault(city, "410");
        }

        // 构建 mainSearchPcConditionForm
        Map<String, Object> conditionForm = new LinkedHashMap<>();
        conditionForm.put("city", cityCode);
        conditionForm.put("dq", cityCode);
        conditionForm.put("key", keyword);
        conditionForm.put("currentPage", 0);
        conditionForm.put("pageSize", 40);
        conditionForm.put("suggestTag", "");
        conditionForm.put("workYearCode", workYearCode);
        conditionForm.put("compId", "");
        conditionForm.put("compKind", "");
        conditionForm.put("compName", "");
        conditionForm.put("compScale", "");
        conditionForm.put("compStage", "");
        conditionForm.put("compTag", "");
        conditionForm.put("eduLevel", "");
        conditionForm.put("otherCity", "");
        conditionForm.put("jobKind", "");
        conditionForm.put("salaryCode", "");
        conditionForm.put("salaryHigh", "");
        conditionForm.put("salaryLow", "");
        conditionForm.put("industry", "");
        conditionForm.put("hrActiveTimeCode", "");

        // 构建 passThroughForm
        Map<String, Object> passThroughForm = new LinkedHashMap<>();
        passThroughForm.put("scene", "input");
        passThroughForm.put("sfrom", "search_job_pc");

        // 最终 data 结构
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mainSearchPcConditionForm", conditionForm);
        data.put("passThroughForm", passThroughForm);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);

        return body;
    }

    private List<JobListing> parseSearchResponse(String responseBody) {
        List<JobListing> jobs = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查 flag 是否成功
            int flag = root.path("flag").asInt(0);
            if (flag != 1) {
                String msg = root.path("msg").asText("未知错误");
                log.warn("[猎聘] 搜索接口返回失败: flag={}, msg={}", flag, msg);
                return jobs;
            }

            // 真实数据结构: root.data.data.jobCardList (注意两层 data)
            JsonNode outerData = root.path("data");
            JsonNode innerData = outerData.path("data");
            JsonNode jobList = innerData.path("jobCardList");

            if (!jobList.isArray()) {
                // 兼容老结构
                jobList = outerData.path("jobCardList");
            }
            if (!jobList.isArray()) {
                log.warn("[猎聘] 响应格式异常，未找到岗位列表");
                return jobs;
            }

            for (JsonNode card : jobList) {
                try {
                    // 每个卡片包含 job 和 comp 两个对象
                    JsonNode job = card.path("job");
                    JsonNode comp = card.path("comp");

                    // 从 dataInfo 中解析 jobKind (URL编码的JSON)
                    String jobKind = "6"; // 默认值
                    String dataInfoStr = card.path("dataInfo").asText("");
                    if (dataInfoStr != null && !dataInfoStr.isEmpty()) {
                        try {
                            String decoded = java.net.URLDecoder.decode(dataInfoStr, "UTF-8");
                            JsonNode dataInfo = objectMapper.readTree(decoded);
                            if (dataInfo.has("jobKind")) {
                                jobKind = dataInfo.path("jobKind").asText("6");
                            }
                        } catch (Exception e) {
                            log.debug("[猎聘] 解析 dataInfo 失败，使用默认 jobKind=6: {}", e.getMessage());
                        }
                    }

                    // 提取 labels 中的学历信息
                    List<String> labels = new ArrayList<>();
                    JsonNode labelsNode = job.path("labels");
                    if (labelsNode.isArray()) {
                        for (JsonNode label : labelsNode) {
                            labels.add(label.asText(""));
                        }
                    }

                    String edu = labels.isEmpty() ? "" : String.join("、", labels);

                    JobListing jobListing = JobListing.builder()
                            .jobId(job.path("jobId").asText(""))
                            .title(job.path("title").asText(""))
                            .company(comp.path("compName").asText(""))
                            .city(job.path("dq").asText(""))
                            .salary(job.path("salary").asText(""))
                            .experienceRequired("")
                            .educationRequired(edu)
                            .description("")
                            .industry(comp.path("compIndustry").asText(""))
                            .detailUrl(job.path("link").asText(""))
                            .publishDate(job.path("refreshTime").asText(""))
                            .jobKind(jobKind)
                            .requiredSkills(Collections.emptyList())
                            .applied(false)
                            .build();

                    if (jobListing.getJobId() != null && !jobListing.getJobId().isEmpty()) {
                        jobs.add(jobListing);
                    }
                } catch (Exception e) {
                    log.warn("[猎聘] 单条岗位解析失败: {}", e.getMessage());
                }
            }
            log.info("[猎聘] 搜索完成: 返回 {} 个岗位", jobs.size());
        } catch (Exception e) {
            log.error("[猎聘] 响应解析失败: {}", e.getMessage(), e);
        }
        return jobs;
    }

    private JobListing parseJobDetail(String responseBody, String jobId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            return JobListing.builder()
                    .jobId(jobId)
                    .title(data.path("jobTitle").asText(""))
                    .company(data.path("compName").asText(""))
                    .city(data.path("city").asText(""))
                    .salary(data.path("salary").asText(""))
                    .experienceRequired(data.path("experience").asText(""))
                    .educationRequired(data.path("eduLevel").asText(""))
                    .description(data.path("jobDesc").asText(""))
                    .industry(data.path("compIndustry").asText(""))
                    .build();
        } catch (Exception e) {
            log.error("[猎聘] 详情解析失败: {}", e.getMessage(), e);
            return null;
        }
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
}
