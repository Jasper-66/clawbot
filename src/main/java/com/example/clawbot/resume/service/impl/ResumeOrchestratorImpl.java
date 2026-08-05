package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.client.LiepinMcpApiClient;
import com.example.clawbot.resume.config.ResumePlatformConfig;
import com.example.clawbot.resume.model.*;
import com.example.clawbot.resume.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// ── 成员7: 流程编排实现 ──
// 这是整个简历投递模块的"指挥家"，串联成员1~6
@Slf4j
@Service
public class ResumeOrchestratorImpl implements ResumeOrchestrator {

    // 注入成员1~6的接口（Spring会自动注入实现类）
    private final ResumeParser resumeParser;            // 成员1
    private final JobSearchClient jobSearchClient;      // 成员2
    private final MatchScorer matchScorer;              // 成员3
    private final ResumeOptimizer resumeOptimizer;      // 成员4
    private final ApplicationClient applicationClient;  // 成员5
    private final ApplicationTracker applicationTracker;// 成员6
    private final ResumePlatformConfig config;
    private final LiepinMcpApiClient liepinMcpClient;

    // 使用@Lazy注解打破循环依赖
    public ResumeOrchestratorImpl(
            ResumeParser resumeParser,
            JobSearchClient jobSearchClient,
            MatchScorer matchScorer,
            @Lazy ResumeOptimizer resumeOptimizer,
            ApplicationClient applicationClient,
            ApplicationTracker applicationTracker,
            ResumePlatformConfig config,
            LiepinMcpApiClient liepinMcpClient) {
        this.resumeParser = resumeParser;
        this.jobSearchClient = jobSearchClient;
        this.matchScorer = matchScorer;
        this.resumeOptimizer = resumeOptimizer;
        this.applicationClient = applicationClient;
        this.applicationTracker = applicationTracker;
        this.config = config;
        this.liepinMcpClient = liepinMcpClient;
    }

    // 存储待确认的投递任务：key=pendingId, value=PendingApplication
    private final ConcurrentHashMap<String, PendingApplication> pendingApplications = new ConcurrentHashMap<>();

    // 存储搜索结果缓存：key=userId, value=最近搜索到的岗位列表
    private final ConcurrentHashMap<String, List<JobListing>> searchResultCache = new ConcurrentHashMap<>();
    // 存储搜索结果缓存的时间戳：key=userId, value=搜索时间(毫秒)
    private final ConcurrentHashMap<String, Long> searchResultTimestamp = new ConcurrentHashMap<>();
    // 搜索缓存有效期：10分钟（避免使用过旧的缓存数据）
    private static final long SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L;

    // 待确认投递任务的内部数据结构
    private record PendingApplication(
            String userId,
            UserProfile profile,
            JobListing job,
            int matchScore,
            String optimizationTip,
            long createdAt
    ) {}

    // ═══════════════════════════════════════════════════
    // 方法1: 搜索岗位
    // ═══════════════════════════════════════════════════
    @Override
    public String searchJobs(String userId, String keyword, String city) {
        log.info("[成员7] searchJobs 开始 | 用户: {} | 关键词: {} | 城市: {}", userId, keyword, city);

        // Step1: 从 ResumeParser 获取用户简历/求职意向
        UserProfile profile = resumeParser.getProfile(userId);
        String experience = profile != null && profile.getExperienceYears() != null
                ? profile.getExperienceYears() + "年" : "不限";
        String salary = profile != null ? profile.getSalaryRange() : "不限";

        // Step2: 调用 JobSearchClient 搜索岗位
        List<JobListing> jobs = jobSearchClient.searchJobs(keyword, city, experience, salary);
        log.info("[成员7] 搜索到 {} 个岗位", jobs.size());

        // Step3: 若结果为空 → 返回提示
        if (jobs.isEmpty()) {
            return "🔍 当前条件未找到匹配岗位，请调整搜索条件后重试。\n\n💡 建议：\n1. 尝试更宽泛的关键词\n2. 扩大搜索城市范围\n3. 调整薪资期望";
        }

        // Step4: 取前5个岗位，格式化为微信可读文本
        StringBuilder sb = new StringBuilder();
        sb.append("📋 为您找到以下岗位：\n\n");

        int limit = Math.min(5, jobs.size());
        for (int i = 0; i < limit; i++) {
            JobListing job = jobs.get(i);
            sb.append(String.format("%d. %s @ %s\n", i + 1, job.getTitle(), job.getCompany()));
            sb.append(String.format("   💰 %s | 📍 %s\n", job.getSalary(), job.getCity()));
            if (job.getExperienceRequired() != null) {
                sb.append(String.format("   📝 经验要求: %s\n", job.getExperienceRequired()));
            }
            sb.append("\n");
        }

        if (jobs.size() > 5) {
            sb.append(String.format("... 还有 %d 个岗位，可使用「自动投递」功能智能匹配\n", jobs.size() - 5));
        }

        sb.append("\n💡 您可以说「帮我投简历」来自动投递匹配的岗位，或者说「投第1个」「投前3个」从搜索结果中选择投递");

        // 缓存搜索结果，供后续 applyFromSearch 使用
        searchResultCache.put(userId, jobs);
        searchResultTimestamp.put(userId, System.currentTimeMillis());
        log.info("[成员7] 搜索结果已缓存，共 {} 个岗位，缓存时间戳已更新", jobs.size());

        log.info("[成员7] searchJobs 完成，返回 {} 字符", sb.length());
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // 方法2: 一键自动投递（核心方法）
    // ═══════════════════════════════════════════════════
    @Override
    public ApplicationResult autoApply(String userId, String userMessage) {
        log.info("[成员7] autoApply 开始 | 用户: {} | 消息: {}", userId, userMessage);

        // ═══ Step1: 获取用户简历 ═══
        // 优先级：猎聘真实简历 > 本地已存简历 > 从消息解析
        log.info("[成员7] Step1: 获取用户简历");
        UserProfile profile = null;

        // 1) 优先从猎聘拉取真实简历（完整度最高）
        String liepinResume = null;
        try {
            liepinResume = liepinMcpClient.getResume();
        } catch (Exception e) {
            log.warn("[成员7] 拉取猎聘简历异常: {}", e.getMessage());
        }
        if (liepinResume != null && !liepinResume.isBlank()) {
            log.info("[成员7] 成功拉取猎聘简历 ({}字符)，解析入库", liepinResume.length());
            profile = resumeParser.parseFromText(userId, liepinResume);
            log.info("[成员7] 猎聘简历解析完成: 姓名={}, 期望职位={}, 学校={}, 学历={}, 技能数={}",
                    profile.getName(), profile.getDesiredPosition(), profile.getSchool(),
                    profile.getEducation(), profile.getSkills() != null ? profile.getSkills().size() : 0);
        } else {
            log.info("[成员7] 猎聘简历拉取为空/失败，回退到本地简历");
        }

        // 2) 回退到本地已存简历
        if (profile == null) {
            profile = resumeParser.getProfile(userId);
            if (profile != null) {
                log.info("[成员7] 使用已存简历: 姓名={}, 期望职位={}", profile.getName(), profile.getDesiredPosition());
            }
        }

        // 3) 都没有则从消息解析
        if (profile == null) {
            log.info("[成员7] 无简历可用，从消息中解析");
            profile = resumeParser.parseFromMessage(userId, userMessage);
            profile = resumeParser.saveProfile(profile);
            log.info("[成员7] 简历解析并保存成功: 姓名={}, 期望职位={}", profile.getName(), profile.getDesiredPosition());
        }

        // ═══ Step2: 搜索匹配岗位 ═══
        log.info("[成员7] Step2: 搜索匹配岗位");
        String keyword = buildSearchKeyword(profile, userMessage);
        String city = profile.getDesiredCity() != null ? profile.getDesiredCity() : extractCity(userMessage);
        String experience = profile.getExperienceYears() != null ? profile.getExperienceYears() + "年" : "不限";
        String salary = profile.getSalaryRange() != null ? profile.getSalaryRange() : "不限";

        List<JobListing> jobs = jobSearchClient.searchJobs(keyword, city, experience, salary);
        log.info("[成员7] 搜索到 {} 个岗位", jobs.size());

        if (jobs.isEmpty()) {
            throw new RuntimeException("未找到匹配岗位，建议调整搜索条件。您可以说「搜索 Java开发 北京」来手动搜索");
        }

        // ═══ Step3: 匹配评分并筛选（门槛取自配置，默认50）═══
        log.info("[成员7] Step3: 人岗匹配评分 (minMatchScore={})", config.getMinMatchScore());
        Map<JobListing, Integer> scoredJobs = matchScorer.scoreAndRank(profile, jobs, config.getMinMatchScore());
        log.info("[成员7] 评分完成，{} 个岗位参与排序", scoredJobs.size());

        if (scoredJobs.isEmpty()) {
            throw new RuntimeException("未找到匹配岗位，建议调整搜索条件。您可以说「搜索 Java开发 北京」来手动搜索");
        }

        // ═══ Step4: 将评分后的岗位列表刷入搜索缓存，供后续按序号投递 ═══
        log.info("[成员7] Step4: 缓存评分后的岗位列表 (共 {} 个)", scoredJobs.size());
        List<JobListing> rankedJobs = new ArrayList<>(scoredJobs.keySet());
        searchResultCache.put(userId, rankedJobs);
        searchResultTimestamp.put(userId, System.currentTimeMillis());
        log.info("[成员7] 岗位列表已缓存，供 applyFromSearch 使用");

        // ═══ Step5: 构造岗位选择列表，等用户确认后再投递 ═══
        log.info("[成员7] Step5: 生成待确认岗位列表");
        String message = buildJobSelectionText(profile, rankedJobs, scoredJobs);

        ApplicationResult result = ApplicationResult.builder()
                .success(false)
                .resumeSummary(buildResumeSummary(profile))
                .message(message)
                .build();

        log.info("[成员7] autoApply 完成 | 返回 {} 个候选岗位，等待用户选择投递", rankedJobs.size());
        return result;
    }

    /**
     * 构建岗位选择列表文本（含简历摘要与匹配度），供用户确认后投递。
     */
    private String buildJobSelectionText(UserProfile profile, List<JobListing> jobs,
                                         Map<JobListing, Integer> scoredJobs) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 已为你筛选出以下匹配岗位：\n\n");

        int limit = Math.min(5, jobs.size());
        for (int i = 0; i < limit; i++) {
            JobListing job = jobs.get(i);
            int score = scoredJobs.getOrDefault(job, 0);
            sb.append(String.format("%d. %s @ %s (匹配度: %d分)\n", i + 1, job.getTitle(), job.getCompany(), score));
            if (job.getSalary() != null && !job.getSalary().isBlank()) {
                sb.append(String.format("   💰 %s\n", job.getSalary()));
            }
            if (job.getCity() != null && !job.getCity().isBlank()) {
                sb.append(String.format("   📍 %s\n", job.getCity()));
            }
            sb.append("\n");
        }

        if (jobs.size() > 5) {
            sb.append(String.format("... 还有 %d 个岗位可投\n", jobs.size() - 5));
        }

        sb.append("💡 请回复序号投递单个岗位（如「1」），或说「全投」「投前2个」批量投递");
        return sb.toString();
    }

    /**
     * 构建简历摘要（逐行展示关键信息），供微信端回显确认。
     */
    private String buildResumeSummary(UserProfile profile) {
        if (profile == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("📄 你的简历：\n");
        sb.append(String.format("👤 %s", profile.getName() != null ? profile.getName() : "未知"));
        if (profile.getSchool() != null && !profile.getSchool().isBlank()) {
            sb.append(String.format(" | %s", profile.getSchool()));
        }
        sb.append(String.format(" | %s\n", profile.getEducation() != null && !profile.getEducation().isBlank()
                ? profile.getEducation() : "学历未知"));
        sb.append(String.format("📍 期望城市: %s\n", profile.getDesiredCity() != null ? profile.getDesiredCity() : "未设置"));
        if (profile.getDesiredPosition() != null && !profile.getDesiredPosition().isBlank()) {
            sb.append(String.format("💼 期望职位: %s\n", profile.getDesiredPosition()));
        }
        if (profile.getSkills() != null && !profile.getSkills().isEmpty()) {
            sb.append(String.format("🛠 技能: %s\n", String.join(", ", profile.getSkills())));
        }
        if (profile.getWorkHistory() != null && !profile.getWorkHistory().isEmpty()) {
            sb.append("🧑‍💻 工作经历：\n");
            for (String w : profile.getWorkHistory()) {
                sb.append("  • ").append(w).append("\n");
            }
        }
        if (profile.getProjectHistory() != null && !profile.getProjectHistory().isEmpty()) {
            sb.append("🚀 项目经历：\n");
            for (String p : profile.getProjectHistory()) {
                sb.append("  • ").append(p).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ═══════════════════════════════════════════════════
    // 方法3: 批量投递
    // ═══════════════════════════════════════════════════
    @Override
    public String batchApply(String userId, String userMessage, int maxCount) {
        log.info("[成员7] batchApply 开始 | 用户: {} | 最大数量: {}", userId, maxCount);

        // ═══ Step1: 获取用户简历 ═══
        UserProfile profile = resumeParser.getProfile(userId);
        if (profile == null) {
            profile = resumeParser.parseFromMessage(userId, userMessage);
            profile = resumeParser.saveProfile(profile);
        }

        // ═══ Step2: 搜索匹配岗位 ═══
        String keyword = profile.getDesiredPosition() != null ? profile.getDesiredPosition() : extractKeyword(userMessage);
        String city = profile.getDesiredCity() != null ? profile.getDesiredCity() : extractCity(userMessage);
        String experience = profile.getExperienceYears() != null ? profile.getExperienceYears() + "年" : "不限";
        String salary = profile.getSalaryRange() != null ? profile.getSalaryRange() : "不限";

        List<JobListing> jobs = jobSearchClient.searchJobs(keyword, city, experience, salary);
        if (jobs.isEmpty()) {
            return "❌ 未找到匹配岗位，建议调整搜索条件";
        }

        // ═══ Step3: 匹配评分并筛选 ═══
        Map<JobListing, Integer> scoredJobs = matchScorer.scoreAndRank(profile, jobs, config.getMinMatchScore());
        if (scoredJobs.isEmpty()) {
            return String.format("❌ 未找到足够匹配的岗位（匹配度均低于%d分）", config.getMinMatchScore());
        }

        // 取前N个匹配岗位
        List<Map.Entry<JobListing, Integer>> topJobs = scoredJobs.entrySet().stream()
                .limit(maxCount)
                .toList();

        log.info("[成员7] 将批量投递 {} 个岗位", topJobs.size());

        // ═══ Step4: 批量投递 ═══
        List<JobListing> jobsToApply = topJobs.stream()
                .map(Map.Entry::getKey)
                .toList();

        List<ApplicationResult> results = applicationClient.batchApply(jobsToApply, profile);

        // 设置匹配分数
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setMatchScore(topJobs.get(i).getValue());
            if (results.get(i).isSuccess()) {
                results.get(i).setAppliedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
        }

        // ═══ Step5: 记录投递 ═══
        int recorded = applicationTracker.recordBatch(results, userId);
        log.info("[成员7] 批量投递记录已保存: {} 条", recorded);

        // ═══ Step6: 格式化返回结果 ═══
        long successCount = results.stream().filter(ApplicationResult::isSuccess).count();
        long failCount = results.size() - successCount;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 批量投递完成：成功 %d 个，失败 %d 个\n\n", successCount, failCount));

        for (int i = 0; i < results.size(); i++) {
            ApplicationResult result = results.get(i);
            JobListing job = topJobs.get(i).getKey();
            int score = topJobs.get(i).getValue();

            if (result.isSuccess()) {
                sb.append(String.format("✅ %d. %s @ %s (匹配度: %d分)\n", i + 1, job.getTitle(), job.getCompany(), score));
            } else {
                sb.append(String.format("❌ %d. %s @ %s - %s\n", i + 1, job.getTitle(), job.getCompany(), result.getMessage()));
            }
        }

        log.info("[成员7] batchApply 完成");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // 方法4: 从搜索结果中投递指定岗位
    // ═══════════════════════════════════════════════════
    @Override
    public String applyFromSearch(String userId, List<Integer> indices, Integer count) {
        log.info("[成员7] applyFromSearch 开始 | 用户: {} | indices={} | count={}", userId, indices, count);

        // Step1: 从缓存获取搜索结果，并校验缓存时效性
        List<JobListing> cachedJobs = searchResultCache.get(userId);
        Long cachedAt = searchResultTimestamp.get(userId);

        if (cachedJobs == null || cachedJobs.isEmpty()) {
            return "❌ 没有找到最近的搜索结果，请先搜索岗位（如「帮我看看河北的Java岗位」）";
        }

        // 校验缓存时效性：超过10分钟的缓存不允许使用，避免用户看到的列表与实际缓存不一致
        if (cachedAt == null || (System.currentTimeMillis() - cachedAt) > SEARCH_CACHE_TTL_MS) {
            log.warn("[成员7] 搜索缓存已过期（缓存时间: {}ms前），拒绝使用旧缓存",
                    cachedAt != null ? (System.currentTimeMillis() - cachedAt) : "未知");
            return "⚠️ 搜索结果已超过10分钟，为避免投递错误岗位，请重新搜索后再投递。\n\n您可以说「帮我看看xx的xx岗位」来重新搜索。";
        }

        log.info("[成员7] 搜索缓存有效（{}ms前），共 {} 个岗位",
                System.currentTimeMillis() - cachedAt, cachedJobs.size());
        // 打印前3个岗位信息，便于调试确认缓存内容是否正确
        for (int i = 0; i < Math.min(3, cachedJobs.size()); i++) {
            JobListing j = cachedJobs.get(i);
            log.info("[成员7]   缓存岗位#{}: {} @ {} | {}", i + 1, j.getTitle(), j.getCompany(), j.getCity());
        }

        // Step2: 确定要投递的岗位
        List<JobListing> jobsToApply = new ArrayList<>();
        List<Integer> invalidIndices = new ArrayList<>();

        if (count != null && count > 0) {
            // 投前 N 个
            int end = Math.min(count, cachedJobs.size());
            for (int i = 0; i < end; i++) {
                jobsToApply.add(cachedJobs.get(i));
            }
        } else if (indices != null && !indices.isEmpty()) {
            // 投指定序号（从1开始）
            for (int idx : indices) {
                if (idx >= 1 && idx <= cachedJobs.size()) {
                    jobsToApply.add(cachedJobs.get(idx - 1));
                } else {
                    invalidIndices.add(idx);
                }
            }
        }

        if (jobsToApply.isEmpty()) {
            if (!invalidIndices.isEmpty()) {
                return String.format("❌ 序号 %s 超出范围，当前搜索结果只有 %d 个岗位",
                        invalidIndices, cachedJobs.size());
            }
            return "❌ 没有找到符合条件的岗位，请检查序号是否正确";
        }

        log.info("[成员7] 选择投递 {} 个岗位", jobsToApply.size());
        for (int i = 0; i < jobsToApply.size(); i++) {
            JobListing j = jobsToApply.get(i);
            log.info("[成员7]   待投递#{}: {} @ {} | jobId={}", i + 1, j.getTitle(), j.getCompany(), j.getJobId());
        }

        // Step3: 获取用户简历
        UserProfile profile = resumeParser.getProfile(userId);
        if (profile == null) {
            return "❌ 请先提供简历信息（如发送简历文字或文件）";
        }

        // Step4: 批量投递
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📋 开始投递 %d 个岗位...\n\n", jobsToApply.size()));

        int successCount = 0;
        int failCount = 0;
        for (int i = 0; i < jobsToApply.size(); i++) {
            JobListing job = jobsToApply.get(i);
            try {
                ApplicationResult result = applicationClient.apply(job, profile);
                result.setMatchScore(0); // 从搜索结果投递不做评分

                if (result.isSuccess()) {
                    result.setAppliedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    applicationTracker.record(result, userId);
                    successCount++;
                    sb.append(String.format("✅ %d. %s @ %s\n", i + 1, job.getTitle(), job.getCompany()));
                } else {
                    failCount++;
                    String msg = result.getMessage() != null ? result.getMessage() : "投递失败";
                    sb.append(String.format("❌ %d. %s @ %s - %s\n", i + 1, job.getTitle(), job.getCompany(), msg));
                }
            } catch (Exception e) {
                failCount++;
                sb.append(String.format("❌ %d. %s @ %s - %s\n", i + 1, job.getTitle(), job.getCompany(), e.getMessage()));
            }
        }

        sb.append(String.format("\n📊 投递完成：成功 %d 个，失败 %d 个", successCount, failCount));

        log.info("[成员7] applyFromSearch 完成 | 成功={} | 失败={}", successCount, failCount);
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // 方法5: 查询投递进度
    // ═══════════════════════════════════════════════════
    @Override
    public String getApplicationProgress(String userId) {
        log.info("[成员7] getApplicationProgress 开始 | 用户: {}", userId);

        // Step1: 获取统计数据
        Map<String, Integer> stats = applicationTracker.getStatistics(userId);
        int total = stats.getOrDefault("total", 0);
        int viewed = stats.getOrDefault("viewed", 0);
        int interview = stats.getOrDefault("interview", 0);
        int rejected = stats.getOrDefault("rejected", 0);
        int offer = stats.getOrDefault("offer", 0);
        int pending = total - viewed - interview - rejected - offer;

        // Step2: 获取最近投递记录
        List<ApplicationRecord> records = applicationTracker.getRecords(userId);

        // Step3: 格式化输出
        StringBuilder sb = new StringBuilder();
        sb.append("📊 求职进度：\n");
        sb.append(String.format("总投递: %d | 已查看: %d | 面试: %d | 待定: %d\n",
                total, viewed, interview, pending));
        if (rejected > 0) {
            sb.append(String.format("被拒绝: %d", rejected));
        }
        if (offer > 0) {
            sb.append(String.format(" | 收到Offer: %d", offer));
        }
        sb.append("\n");

        if (!records.isEmpty()) {
            sb.append("\n📋 最近投递：\n");
            int limit = Math.min(5, records.size());
            for (int i = 0; i < limit; i++) {
                ApplicationRecord record = records.get(i);
                String statusEmoji = getStatusEmoji(record.getStatus());
                sb.append(String.format("%d. %s %s @ %s (%s)\n",
                        i + 1, statusEmoji, record.getJobTitle(), record.getCompany(), record.getAppliedAt()));
            }
            if (records.size() > 5) {
                sb.append(String.format("... 还有 %d 条记录\n", records.size() - 5));
            }
        } else {
            sb.append("\n暂无投递记录，您可以说「帮我投简历」开始求职");
        }

        log.info("[成员7] getApplicationProgress 完成");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // 方法5: 用户确认交互
    // ═══════════════════════════════════════════════════
    @Override
    public boolean requestUserConfirmation(String userId, JobListing job, int matchScore) {
        // 这个方法在当前架构中不直接使用，确认流程通过LLM Function Calling实现
        // 返回true表示已发送确认请求
        log.info("[成员7] requestUserConfirmation | 用户: {} | 岗位: {} @ {} | 匹配度: {}",
                userId, job.getTitle(), job.getCompany(), matchScore);
        return true;
    }

    // ═══════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════

    /**
     * 从用户消息中提取岗位关键词
     */
    private String extractKeyword(String message) {
        // 移除常见的动词和助词，提取核心关键词
        String cleaned = message
                .replaceAll("(帮我|我想|我要|请|麻烦|投递|投|找工作|求职|找|搜索|岗位|职位|工作)", "")
                .trim();
        return cleaned.isEmpty() ? "Java开发" : cleaned;
    }

    /**
     * 构建搜索关键词：期望职位太泛（如"软件开发"）时，结合核心技能栈生成更精准的关键词。
     * 例：期望职位"软件开发"+技能["Java","SpringBoot","MySQL"] → "Java 软件开发"
     */
    private String buildSearchKeyword(UserProfile profile, String userMessage) {
        String desired = profile.getDesiredPosition();
        if (desired != null && !desired.isBlank() && !isGenericPosition(desired)) {
            return desired;
        }

        // 从技能栈中挑出核心技术栈（编程语言/框架），与期望职位拼成搜索词
        List<String> skills = profile.getSkills() != null ? profile.getSkills() : List.of();
        String coreSkill = null;
        for (String s : skills) {
            if (s.toLowerCase().contains("java")) { coreSkill = "Java"; break; }
        }
        if (coreSkill == null) {
            for (String s : skills) {
                if (s.toLowerCase().contains("python") || s.toLowerCase().contains("spring")
                        || s.toLowerCase().contains("vue") || s.toLowerCase().contains("前端")) {
                    coreSkill = s;
                    break;
                }
            }
        }

        if (coreSkill != null) {
            String base = (desired != null && !desired.isBlank()) ? desired : extractKeyword(userMessage);
            log.info("[成员7] 期望职位较泛({})，结合技能栈生成精准关键词: {}{}",
                    desired, coreSkill, base);
            return coreSkill + " " + base;
        }
        return desired != null && !desired.isBlank() ? desired : extractKeyword(userMessage);
    }

    /** 判断期望职位是否过于宽泛（难以定位具体岗位） */
    private boolean isGenericPosition(String position) {
        if (position == null || position.isBlank()) return true;
        return position.equals("软件开发") || position.equals("软件工程师")
                || position.equals("开发") || position.equals("工程师")
                || position.equals("软件") || position.equals("技术")
                || position.contains("互联网") || position.contains("it");
    }

    /**
     * 从用户消息中提取城市
     */
    private String extractCity(String message) {
        // 常见城市列表
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "苏州", "天津", "重庆"};
        for (String city : cities) {
            if (message.contains(city)) {
                return city;
            }
        }
        return "北京"; // 默认城市
    }

    /**
     * 获取状态对应的emoji
     */
    private String getStatusEmoji(String status) {
        if (status == null) return "⏳";
        return switch (status.toUpperCase()) {
            case "SUBMITTED" -> "📤";
            case "VIEWED" -> "👀";
            case "INTERVIEW" -> "🎯";
            case "REJECTED" -> "❌";
            case "OFFER" -> "🎉";
            default -> "⏳";
        };
    }
}
