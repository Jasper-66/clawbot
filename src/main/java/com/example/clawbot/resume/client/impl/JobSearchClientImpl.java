package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.config.ResumePlatformConfig;
import com.example.clawbot.resume.model.JobListing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 腾讯招聘岗位搜索客户端实现。
 *
 * <p>对接腾讯官方招聘平台（careers.tencent.com）的公开 API，实现以下能力：</p>
 * <ul>
 *   <li>岗位搜索 — 按关键词、城市、经验、薪资筛选职位</li>
 *   <li>岗位详情 — 根据岗位ID获取完整JD信息</li>
 *   <li>投递校验 — 降级实现，腾讯公开接口不支持查询投递状态</li>
 * </ul>
 *
 * <p>降级策略：所有接口调用异常、解析失败、参数非法场景，
 * 打印错误日志后返回空结果或 null，不向上抛出异常，避免主流程中断。</p>
 *
 * <p>API 文档参考：
 * 搜索 — GET /tencentcareer/api/post/Query
 * 详情 — GET /tencentcareer/api/post/ByPostId</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "tencent", matchIfMissing = true)
public class JobSearchClientImpl implements JobSearchClient {

    /**
     * 平台配置，提供 baseUrl（API 基础地址）等配置项。
     * 腾讯招聘公开接口无需鉴权，apiKey 可忽略。
     */
    @Autowired
    private ResumePlatformConfig config;

    /**
     * Spring HTTP 客户端，用于向腾讯招聘 API 发起请求。
     */
    @Autowired
    private RestTemplate restTemplate;

    /**
     * Jackson JSON 解析工具，用于解析腾讯 API 返回的 JSON 响应。
     */
    @Autowired
    private ObjectMapper objectMapper;

    // ─────────────────────────────────────────────
    // 腾讯招聘 API 常量定义
    // ─────────────────────────────────────────────

    /** 岗位搜索接口路径 */
    private static final String SEARCH_PATH = "/tencentcareer/api/post/Query";

    /** 岗位详情接口路径 */
    private static final String DETAIL_PATH = "/tencentcareer/api/post/ByPostId";

    /** 每页返回数量上限，腾讯接口最大支持 100 */
    private static final int PAGE_SIZE = 20;

    /**
     * 搜索匹配岗位。
     *
     * <p>调用腾讯招聘岗位搜索接口，将入参映射为腾讯接口对应的查询参数，
     * 解析响应 JSON 并逐个转换为项目标准的 JobListing 对象。</p>
     *
     * @param keyword     职位关键词，如 "Java"、"产品经理"
     * @param city        工作城市，如 "深圳"、"北京"
     * @param experience  工作经验要求，如 "3-5年"
     * @param salaryRange 薪资范围（腾讯公开接口不支持薪资筛选，此参数仅做日志记录）
     * @return 匹配的岗位列表，无结果或异常时返回空列表
     */
    @Override
    public List<JobListing> searchJobs(String keyword, String city, String experience, String salaryRange) {
        // ── 第一步：参数校验，关键词为空时直接返回空列表 ──
        if (keyword == null || keyword.isBlank()) {
            log.warn("[腾讯招聘] searchJobs 入参非法：keyword 为空");
            return Collections.emptyList();
        }

        try {
            // ── 第二步：拼接请求地址 ──
            // baseUrl 从配置读取，不硬编码，便于切换环境
            String baseUrl = config.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                // 如果未配置 baseUrl，使用腾讯招聘默认域名
                baseUrl = "https://careers.tencent.com";
            }

            // ── 第三步：构造请求 URL 和查询参数 ──
            // 腾讯接口使用 GET 请求，参数通过 URL Query String 传递
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(baseUrl).append(SEARCH_PATH);
            urlBuilder.append("?pageIndex=1");              // 默认第一页
            urlBuilder.append("&pageSize=").append(PAGE_SIZE);  // 每页数量
            urlBuilder.append("&language=zh-cn");           // 中文
            urlBuilder.append("&area=cn");                  // 中国大陆

            // 添加关键词参数，URL 编码由 RestTemplate 自动处理
            urlBuilder.append("&keyword=").append(keyword);

            // 添加城市筛选参数（腾讯接口通过 locationId 筛选城市）
            // 城市名需要映射为腾讯接口的 locationId，常见映射：
            //   深圳=1, 北京=2, 上海=3, 广州=4, 成都=5, 杭州=6 等
            // 如果城市不在映射表中，不传此参数（搜索全部城市）
            String locationId = mapCityToLocationId(city);
            if (locationId != null) {
                urlBuilder.append("&locationId=").append(locationId);
            }

            String requestUrl = urlBuilder.toString();
            log.info("[腾讯招聘] 发起岗位搜索请求: keyword={}, city={}, url={}", keyword, city, requestUrl);

            // ── 第四步：发送 HTTP GET 请求 ──
            String responseJson = restTemplate.getForObject(requestUrl, String.class);

            // ── 第五步：校验响应是否为空 ──
            if (responseJson == null || responseJson.isBlank()) {
                log.warn("[腾讯招聘] 搜索接口返回空响应: keyword={}, city={}", keyword, city);
                return Collections.emptyList();
            }

            // ── 第六步：解析 JSON 响应 ──
            JsonNode root = objectMapper.readTree(responseJson);

            // 检查业务状态码，腾讯接口 Code=200 表示成功
            int code = root.path("Code").asInt(-1);
            if (code != 200) {
                log.warn("[腾讯招聘] 搜索接口返回错误码: code={}, keyword={}, city={}", code, keyword, city);
                return Collections.emptyList();
            }

            // ── 第七步：提取岗位列表节点 ──
            JsonNode postsNode = root.path("Data").path("Posts");
            if (!postsNode.isArray() || postsNode.isEmpty()) {
                log.info("[腾讯招聘] 搜索无结果: keyword={}, city={}, count=0", keyword, city);
                return Collections.emptyList();
            }

            // ── 第八步：遍历岗位列表，逐个映射为 JobListing 对象 ──
            List<JobListing> results = new ArrayList<>();
            for (JsonNode postNode : postsNode) {
                try {
                    JobListing job = mapToJobListing(postNode);
                    results.add(job);
                } catch (Exception e) {
                    // 单条岗位解析失败不影响其他岗位，打印日志后跳过
                    log.warn("[腾讯招聘] 单条岗位解析失败，已跳过: postId={}, error={}",
                            postNode.path("PostId").asText("unknown"), e.getMessage());
                }
            }

            // ── 第九步：记录搜索结果日志 ──
            int totalCount = root.path("Data").path("Count").asInt(0);
            log.info("[腾讯招聘] 搜索完成: keyword={}, city={}, 返回={}, 总计={}",
                    keyword, city, results.size(), totalCount);

            return results;

        } catch (Exception e) {
            // ── 异常降级：打印错误日志，返回空列表，不向上抛异常 ──
            log.error("[腾讯招聘] 搜索接口调用异常: keyword={}, city={}, error={}",
                    keyword, city, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取单条岗位详情。
     *
     * <p>调用腾讯招聘岗位详情接口，根据 postId 获取完整岗位信息，
     * 包含搜索接口未返回的 Requirement（岗位要求）等详细字段。</p>
     *
     * @param jobId 岗位ID（即腾讯接口的 PostId）
     * @return 岗位详情对象，不存在或异常时返回 null
     */
    @Override
    public JobListing getJobDetail(String jobId) {
        // ── 第一步：参数校验 ──
        if (jobId == null || jobId.isBlank()) {
            log.warn("[腾讯招聘] getJobDetail 入参非法：jobId 为空");
            return null;
        }

        try {
            // ── 第二步：拼接请求地址 ──
            String baseUrl = config.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://careers.tencent.com";
            }

            // 构造详情接口 URL，通过 postId 参数查询单条岗位
            String requestUrl = baseUrl + DETAIL_PATH
                    + "?postId=" + jobId
                    + "&language=zh-cn";

            log.info("[腾讯招聘] 发起岗位详情请求: jobId={}, url={}", jobId, requestUrl);

            // ── 第三步：发送 HTTP GET 请求 ──
            String responseJson = restTemplate.getForObject(requestUrl, String.class);

            // ── 第四步：校验响应 ──
            if (responseJson == null || responseJson.isBlank()) {
                log.warn("[腾讯招聘] 详情接口返回空响应: jobId={}", jobId);
                return null;
            }

            // ── 第五步：解析 JSON 响应 ──
            JsonNode root = objectMapper.readTree(responseJson);

            // 检查业务状态码
            int code = root.path("Code").asInt(-1);
            if (code != 200) {
                log.warn("[腾讯招聘] 详情接口返回错误码: code={}, jobId={}", code, jobId);
                return null;
            }

            // ── 第六步：提取岗位数据节点 ──
            JsonNode dataNode = root.path("Data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                log.warn("[腾讯招聘] 岗位不存在: jobId={}", jobId);
                return null;
            }

            // ── 第七步：映射为 JobListing 对象 ──
            // 详情接口比搜索接口多返回 Requirement（岗位要求）字段
            JobListing job = mapToJobListing(dataNode);

            log.info("[腾讯招聘] 岗位详情获取成功: jobId={}, title={}", jobId, job.getTitle());
            return job;

        } catch (Exception e) {
            // ── 异常降级：打印错误日志，返回 null，不向上抛异常 ──
            log.error("[腾讯招聘] 详情接口调用异常: jobId={}, error={}", jobId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 检查是否已投递该岗位。
     *
     * <p>腾讯公开接口不支持查询投递状态，此处做降级实现，默认返回 false。
     * 实际重复投递校验由上层投递记录模块（ApplicationTracker）通过本地数据库完成。</p>
     *
     * @param jobId  岗位ID
     * @param userId 用户ID
     * @return 始终返回 false（降级实现）
     */
    @Override
    public boolean hasApplied(String jobId, String userId) {
        // ── 降级实现：腾讯公开接口无投递状态查询能力 ──
        // 实际重复投递校验由上层 ApplicationTracker 通过本地投递记录表完成
        // 保留方法完整结构，方便后续对接腾讯内部接口时替换逻辑
        log.debug("[腾讯招聘] hasApplied 降级返回 false（腾讯公开接口不支持投递状态查询）: jobId={}, userId={}", jobId, userId);
        return false;
    }

    // ─────────────────────────────────────────────
    // 私有辅助方法
    // ─────────────────────────────────────────────

    /**
     * 将腾讯 API 返回的岗位 JSON 节点映射为项目标准的 JobListing 对象。
     *
     * <p>字段映射规则：</p>
     * <ul>
     *   <li>PostId → jobId（岗位ID）</li>
     *   <li>RecruitPostName → title（职位名称）</li>
     *   <li>ComName → company（公司名称），子公司用 ComName，否则固定填充「腾讯」</li>
     *   <li>LocationName → city（工作城市）</li>
     *   <li>Responsibility + Requirement → description（岗位描述，拼接职责和要求）</li>
     *   <li>RequireWorkYearsName → experienceRequired（经验要求）</li>
     *   <li>CategoryName → industry（行业/职位类别）</li>
     *   <li>PostURL → detailUrl（岗位详情链接）</li>
     *   <li>LastUpdateTime → publishDate（发布日期）</li>
     * </ul>
     *
     * @param node 腾讯 API 返回的单条岗位 JSON 节点
     * @return 映射后的 JobListing 对象
     */
    private JobListing mapToJobListing(JsonNode node) {
        // ── 岗位ID：腾讯接口使用 PostId 字段 ──
        String postId = node.path("PostId").asText(null);

        // ── 职位名称：RecruitPostName 字段 ──
        String postName = node.path("RecruitPostName").asText(null);

        // ── 公司名称：优先使用 ComName（子公司有独立名称），为空时固定填充「腾讯」 ──
        // 设计思路：腾讯集团旗下有多个子公司（如腾讯云智），ComName 会显示子公司名
        // 当 ComName 为空时，说明是腾讯本部岗位，统一填充「腾讯」
        String comName = node.path("ComName").asText("");
        String company = comName.isBlank() ? "腾讯" : comName;

        // ── 工作城市：LocationName 字段 ──
        String locationName = node.path("LocationName").asText(null);

        // ── 岗位描述：拼接 Responsibility（职责）和 Requirement（要求）──
        // 搜索接口只返回 Responsibility，详情接口额外返回 Requirement
        // 两部分拼接后作为完整的岗位描述
        StringBuilder descBuilder = new StringBuilder();
        String responsibility = node.path("Responsibility").asText("");
        String requirement = node.path("Requirement").asText("");

        if (!responsibility.isBlank()) {
            descBuilder.append("【岗位职责】\n").append(responsibility);
        }
        if (!requirement.isBlank()) {
            if (descBuilder.length() > 0) {
                descBuilder.append("\n\n");
            }
            descBuilder.append("【岗位要求】\n").append(requirement);
        }

        // ── 经验要求：RequireWorkYearsName 字段，如 "三年以上工作经验" ──
        String workYears = node.path("RequireWorkYearsName").asText(null);

        // ── 职位类别：CategoryName 字段，如 "技术"、"产品"、"设计" ──
        String categoryName = node.path("CategoryName").asText(null);

        // ── 岗位详情链接：PostURL 字段 ──
        String postUrl = node.path("PostURL").asText(null);

        // ── 发布日期：LastUpdateTime 字段，如 "2026年07月09日" ──
        String lastUpdateTime = node.path("LastUpdateTime").asText(null);

        // ── 业务线/部门：BGName 字段，如 "CSIG"（云与智慧产业事业群）──
        String bgName = node.path("BGName").asText("");
        String productName = node.path("ProductName").asText("");

        // ── 构建并返回 JobListing 对象 ──
        return JobListing.builder()
                .jobId(postId)
                .title(postName)
                .company(company)
                .city(locationName)
                .salary(null)  // 腾讯公开接口不返回薪资信息，设为 null
                .description(descBuilder.toString())
                .experienceRequired(workYears)
                .educationRequired(null)  // 腾讯搜索接口不返回学历要求，详情接口可能包含
                .industry(categoryName)
                .detailUrl(postUrl)
                .publishDate(lastUpdateTime)
                .applied(false)  // 默认未投递
                .build();
    }

    /**
     * 将城市名称映射为腾讯接口的 locationId。
     *
     * <p>腾讯招聘 API 使用数字 ID 筛选城市，常见映射如下：</p>
     * <ul>
     *   <li>1 → 深圳</li>
     *   <li>2 → 北京</li>
     *   <li>3 → 上海</li>
     *   <li>4 → 广州</li>
     *   <li>5 → 成都</li>
     *   <li>6 → 杭州</li>
     * </ul>
     *
     * @param city 城市中文名称
     * @return locationId 字符串，城市不在映射表中时返回 null（搜索全部城市）
     */
    private String mapCityToLocationId(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }

        // ── 城市名标准化：去除空格、转小写后匹配 ──
        String normalizedCity = city.trim();

        return switch (normalizedCity) {
            case "深圳" -> "1";
            case "北京" -> "2";
            case "上海" -> "3";
            case "广州" -> "4";
            case "成都" -> "5";
            case "杭州" -> "6";
            // 未匹配到的城市返回 null，不传 locationId 参数，搜索全部城市
            default -> {
                log.debug("[腾讯招聘] 城市 '{}' 未在映射表中，将搜索全部城市", city);
                yield null;
            }
        };
    }
}
