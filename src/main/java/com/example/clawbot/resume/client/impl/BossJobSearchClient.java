package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.JobSearchClient;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Boss直聘岗位搜索客户端实现。
 *
 * <p>对接Boss直聘API实现岗位搜索能力。由于Boss直聘没有公开API，当前实现：</p>
 * <ul>
 *   <li>方式1：通过Boss直聘网页API（需要Cookie认证）</li>
 *   <li>方式2：RPA模式（Selenium/Playwright模拟浏览器）</li>
 *   <li>方式3：Mock模式（开发测试用）</li>
 * </ul>
 *
 * <p>配置方式：在application.properties中设置</p>
 * <pre>
 * resume.platform.provider=boss
 * resume.platform.base-url=https://www.zhipin.com
 * resume.platform.api-key=your_cookie_here
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "boss")
public class BossJobSearchClient implements JobSearchClient {

    @Autowired
    private ResumePlatformConfig config;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** Boss直聘搜索API路径 */
    private static final String SEARCH_API = "/wapi/zpgeek/search/joblist.json";

    /** Boss直聘职位详情API路径 */
    private static final String DETAIL_API = "/wapi/zpgeek/job/card.json";

    /** 城市编码映射 */
    private static final java.util.Map<String, String> CITY_CODES = new java.util.HashMap<>() {{
        put("北京", "101010100");
        put("上海", "101020100");
        put("广州", "101280100");
        put("深圳", "101280600");
        put("杭州", "101210100");
        put("成都", "101270100");
        put("武汉", "101200100");
        put("南京", "101190100");
        put("西安", "101110100");
        put("苏州", "101190400");
        put("天津", "101030100");
        put("重庆", "101040100");
        put("长沙", "101250100");
        put("郑州", "101180100");
        put("合肥", "101220100");
        put("厦门", "101230200");
        put("青岛", "101120200");
        put("大连", "101070200");
        put("宁波", "101210400");
        put("福州", "101230100");
        put("济南", "101120100");
    }};

    @Override
    public List<JobListing> searchJobs(String keyword, String city, String experience, String salaryRange) {
        if (keyword == null || keyword.isBlank()) {
            log.warn("[Boss直聘] searchJobs 入参非法：keyword 为空");
            return Collections.emptyList();
        }

        String provider = config.getProvider();
        log.info("[Boss直聘] 发起岗位搜索: keyword={}, city={}, experience={}, salary={}", keyword, city, experience, salaryRange);

        // 如果配置为mock模式，返回模拟数据
        if ("mock".equals(provider) || config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            return searchJobsMock(keyword, city, experience, salaryRange);
        }

        try {
            // 构建请求URL
            String cityCode = CITY_CODES.getOrDefault(city, "101010100"); // 默认北京
            String url = buildSearchUrl(keyword, cityCode, experience, salaryRange);

            // 设置请求头（模拟浏览器）
            HttpHeaders headers = buildHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.info("[Boss直聘] 请求URL: {}", url);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("[Boss直聘] 搜索请求失败: status={}", response.getStatusCode());
                return Collections.emptyList();
            }

            // 解析响应
            return parseSearchResponse(response.getBody());

        } catch (Exception e) {
            log.error("[Boss直聘] 搜索异常: {}", e.getMessage(), e);
            // 降级到mock模式
            log.info("[Boss直聘] 降级到mock模式");
            return searchJobsMock(keyword, city, experience, salaryRange);
        }
    }

    @Override
    public JobListing getJobDetail(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            log.warn("[Boss直聘] getJobDetail 入参非法：jobId 为空");
            return null;
        }

        log.info("[Boss直聘] 获取岗位详情: jobId={}", jobId);

        // Mock模式
        if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
            return getJobDetailMock(jobId);
        }

        try {
            String url = config.getBaseUrl() + DETAIL_API + "?securityId=&lid=&sessionId=&keyword=&jobId=" + jobId;

            HttpHeaders headers = buildHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return null;
            }

            return parseJobDetail(response.getBody(), jobId);

        } catch (Exception e) {
            log.error("[Boss直聘] 获取详情异常: jobId={}, error={}", jobId, e.getMessage(), e);
            return getJobDetailMock(jobId);
        }
    }

    @Override
    public boolean hasApplied(String jobId, String userId) {
        // Boss直聘没有公开的投递状态查询API
        // 实际重复投递校验由ApplicationTracker通过本地数据库完成
        log.debug("[Boss直聘] hasApplied 返回false（需本地校验）: jobId={}, userId={}", jobId, userId);
        return false;
    }

    // ═══════════════════════════════════════════════════
    // Mock实现 - 开发测试用
    // ═══════════════════════════════════════════════════

    private List<JobListing> searchJobsMock(String keyword, String city, String experience, String salaryRange) {
        log.info("[Boss直聘-Mock] 生成模拟岗位数据: keyword={}, city={}", keyword, city);

        List<JobListing> jobs = new ArrayList<>();
        Random random = new Random();

        // 生成5-10个模拟岗位
        int count = 5 + random.nextInt(6);
        String[] companies = {"字节跳动", "阿里巴巴", "腾讯", "美团", "京东", "百度", "网易", "拼多多", "小红书", "快手"};
        String[] salaries = {"15k-25k", "20k-35k", "25k-40k", "30k-50k", "35k-60k", "40k-70k"};

        for (int i = 0; i < count; i++) {
            String company = companies[random.nextInt(companies.length)];
            String salary = salaries[random.nextInt(salaries.length)];

            jobs.add(JobListing.builder()
                    .jobId("BOSS-MOCK-" + System.currentTimeMillis() + "-" + i)
                    .title(keyword + "工程师")
                    .company(company)
                    .city(city)
                    .salary(salary)
                    .experienceRequired(experience != null ? experience : "3-5年")
                    .educationRequired("本科")
                    .description("【岗位职责】\n1. 负责" + keyword + "相关系统的设计与开发\n2. 参与技术方案设计和代码评审\n3. 持续优化系统性能和稳定性\n\n" +
                            "【任职要求】\n1. 计算机相关专业本科及以上学历\n2. 3年以上" + keyword + "开发经验\n3. 熟悉主流技术栈\n4. 良好的沟通能力和团队协作精神")
                    .requiredSkills(List.of(keyword, "Java", "Spring Boot", "MySQL"))
                    .industry("互联网")
                    .detailUrl("https://www.zhipin.com/job_detail/MOCK" + i + ".html")
                    .publishDate("2026-07-" + (20 + random.nextInt(10)))
                    .applied(false)
                    .build());
        }

        log.info("[Boss直聘-Mock] 生成 {} 个模拟岗位", jobs.size());
        return jobs;
    }

    private JobListing getJobDetailMock(String jobId) {
        return JobListing.builder()
                .jobId(jobId)
                .title("Mock岗位详情")
                .company("Mock公司")
                .city("北京")
                .salary("20k-35k")
                .experienceRequired("3-5年")
                .educationRequired("本科")
                .description("这是一个Mock岗位详情，用于开发测试。\n\n【岗位职责】\n1. 负责系统开发\n2. 参与技术方案设计\n\n【任职要求】\n1. 本科及以上学历\n2. 3年以上开发经验")
                .requiredSkills(List.of("Java", "Spring Boot", "MySQL"))
                .industry("互联网")
                .build();
    }

    // ═══════════════════════════════════════════════════
    // 真实API调用辅助方法
    // ═══════════════════════════════════════════════════

    private String buildSearchUrl(String keyword, String cityCode, String experience, String salaryRange) {
        StringBuilder url = new StringBuilder();
        url.append(config.getBaseUrl()).append(SEARCH_API);
        url.append("?query=").append(keyword);
        url.append("&city=").append(cityCode);
        url.append("&page=1");
        url.append("&pageSize=15");

        // 经验要求映射
        if (experience != null && !experience.isBlank()) {
            String expCode = mapExperienceToCode(experience);
            if (expCode != null) {
                url.append("&experience=").append(expCode);
            }
        }

        // 薪资范围映射
        if (salaryRange != null && !salaryRange.isBlank()) {
            String salaryCode = mapSalaryToCode(salaryRange);
            if (salaryCode != null) {
                url.append("&salary=").append(salaryCode);
            }
        }

        return url.toString();
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.set("Referer", "https://www.zhipin.com/");
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        // 如果配置了Cookie/API Key，添加到请求头
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            headers.set("Cookie", config.getApiKey());
        }

        return headers;
    }

    private List<JobListing> parseSearchResponse(String responseBody) {
        List<JobListing> jobs = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int code = root.path("code").asInt(-1);

            if (code != 0) {
                log.warn("[Boss直聘] 搜索返回错误码: code={}, message={}", code, root.path("message").asText());
                return Collections.emptyList();
            }

            JsonNode jobList = root.path("zpData").path("jobList");
            if (!jobList.isArray()) {
                log.warn("[Boss直聘] 响应格式异常：缺少jobList数组");
                return Collections.emptyList();
            }

            for (JsonNode jobNode : jobList) {
                try {
                    JobListing job = JobListing.builder()
                            .jobId(jobNode.path("encryptJobId").asText())
                            .title(jobNode.path("jobName").asText())
                            .company(jobNode.path("brandName").asText())
                            .city(jobNode.path("cityName").asText())
                            .district(jobNode.path("areaDistrict").asText())
                            .salary(jobNode.path("salaryDesc").asText())
                            .experienceRequired(jobNode.path("jobExperience").asText())
                            .educationRequired(jobNode.path("jobDegree").asText())
                            .description(buildJobDescription(jobNode))
                            .requiredSkills(parseSkills(jobNode.path("skills")))
                            .industry(jobNode.path("brandIndustry").asText())
                            .companySize(jobNode.path("brandScaleName").asText())
                            .detailUrl("https://www.zhipin.com/job_detail/" + jobNode.path("encryptJobId").asText() + ".html")
                            .publishDate(jobNode.path("postDate").asText())
                            .applied(false)
                            .build();

                    jobs.add(job);
                } catch (Exception e) {
                    log.warn("[Boss直聘] 单条岗位解析失败: {}", e.getMessage());
                }
            }

            log.info("[Boss直聘] 搜索完成: 返回 {} 个岗位", jobs.size());

        } catch (Exception e) {
            log.error("[Boss直聘] 响应解析失败: {}", e.getMessage(), e);
        }

        return jobs;
    }

    private JobListing parseJobDetail(String responseBody, String jobId) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("zpData");

            if (data.isMissingNode() || data.isNull()) {
                return null;
            }

            return JobListing.builder()
                    .jobId(jobId)
                    .title(data.path("jobName").asText())
                    .company(data.path("brandName").asText())
                    .city(data.path("cityName").asText())
                    .salary(data.path("salaryDesc").asText())
                    .experienceRequired(data.path("jobExperience").asText())
                    .educationRequired(data.path("jobDegree").asText())
                    .description(data.path("postDescription").asText())
                    .requiredSkills(parseSkills(data.path("skills")))
                    .industry(data.path("brandIndustry").asText())
                    .build();

        } catch (Exception e) {
            log.error("[Boss直聘] 详情解析失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildJobDescription(JsonNode jobNode) {
        StringBuilder desc = new StringBuilder();

        String postDescription = jobNode.path("postDescription").asText("");
        if (!postDescription.isEmpty()) {
            desc.append(postDescription);
        }

        // 添加福利标签
        JsonNode welfareList = jobNode.path("welfareList");
        if (welfareList.isArray() && !welfareList.isEmpty()) {
            desc.append("\n\n【福利待遇】\n");
            for (JsonNode welfare : welfareList) {
                desc.append("• ").append(welfare.asText()).append("\n");
            }
        }

        return desc.toString();
    }

    private List<String> parseSkills(JsonNode skillsNode) {
        List<String> skills = new ArrayList<>();
        if (skillsNode.isArray()) {
            for (JsonNode skill : skillsNode) {
                String skillText = skill.isObject() ? skill.path("name").asText() : skill.asText();
                if (!skillText.isEmpty()) {
                    skills.add(skillText);
                }
            }
        }
        return skills;
    }

    private String mapExperienceToCode(String experience) {
        if (experience == null) return null;
        // Boss直聘经验编码：1=不限, 2=应届, 3=1-3年, 4=3-5年, 5=5-10年, 6=10年以上
        if (experience.contains("应届") || experience.contains("实习")) return "2";
        if (experience.contains("1") && experience.contains("3")) return "3";
        if (experience.contains("3") && experience.contains("5")) return "4";
        if (experience.contains("5") && experience.contains("10")) return "5";
        if (experience.contains("10")) return "6";
        return "1"; // 不限
    }

    private String mapSalaryToCode(String salaryRange) {
        if (salaryRange == null) return null;
        // Boss直聘薪资编码：1=不限, 2=3k以下, 3=3-5k, ..., 8=50k以上
        // 简化处理，返回null让API不限制
        return null;
    }
}
