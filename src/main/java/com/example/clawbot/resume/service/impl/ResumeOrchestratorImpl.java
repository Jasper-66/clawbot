package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.JobSearchClient;
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

    // 使用@Lazy注解打破循环依赖
    public ResumeOrchestratorImpl(
            ResumeParser resumeParser,
            JobSearchClient jobSearchClient,
            MatchScorer matchScorer,
            @Lazy ResumeOptimizer resumeOptimizer,
            ApplicationClient applicationClient,
            ApplicationTracker applicationTracker) {
        this.resumeParser = resumeParser;
        this.jobSearchClient = jobSearchClient;
        this.matchScorer = matchScorer;
        this.resumeOptimizer = resumeOptimizer;
        this.applicationClient = applicationClient;
        this.applicationTracker = applicationTracker;
    }

    // 存储待确认的投递任务：key=pendingId, value=PendingApplication
    private final ConcurrentHashMap<String, PendingApplication> pendingApplications = new ConcurrentHashMap<>();

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

        sb.append("\n💡 您可以说「帮我投简历」来自动投递匹配的岗位");

        log.info("[成员7] searchJobs 完成，返回 {} 字符", sb.length());
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // 方法2: 一键自动投递（核心方法）
    // ═══════════════════════════════════════════════════
    @Override
    public ApplicationResult autoApply(String userId, String userMessage) {
        log.info("[成员7] autoApply 开始 | 用户: {} | 消息: {}", userId, userMessage);

        // ═══ Step1: 获取或解析用户简历 ═══
        log.info("[成员7] Step1: 获取用户简历");
        UserProfile profile = resumeParser.getProfile(userId);
        if (profile == null) {
            log.info("[成员7] 用户无已存简历，从消息中解析");
            profile = resumeParser.parseFromMessage(userId, userMessage);
            profile = resumeParser.saveProfile(profile);
            log.info("[成员7] 简历解析并保存成功: 姓名={}, 期望职位={}", profile.getName(), profile.getDesiredPosition());
        } else {
            log.info("[成员7] 使用已存简历: 姓名={}, 期望职位={}", profile.getName(), profile.getDesiredPosition());
        }

        // ═══ Step2: 搜索匹配岗位 ═══
        log.info("[成员7] Step2: 搜索匹配岗位");
        String keyword = profile.getDesiredPosition() != null ? profile.getDesiredPosition() : extractKeyword(userMessage);
        String city = profile.getDesiredCity() != null ? profile.getDesiredCity() : extractCity(userMessage);
        String experience = profile.getExperienceYears() != null ? profile.getExperienceYears() + "年" : "不限";
        String salary = profile.getSalaryRange() != null ? profile.getSalaryRange() : "不限";

        List<JobListing> jobs = jobSearchClient.searchJobs(keyword, city, experience, salary);
        log.info("[成员7] 搜索到 {} 个岗位", jobs.size());

        if (jobs.isEmpty()) {
            throw new RuntimeException("未找到匹配岗位，建议调整搜索条件。您可以说「搜索 Java开发 北京」来手动搜索");
        }

        // ═══ Step3: 匹配评分并筛选 ═══
        log.info("[成员7] Step3: 人岗匹配评分");
        Map<JobListing, Integer> scoredJobs = matchScorer.scoreAndRank(profile, jobs, 60);
        log.info("[成员7] 评分完成，{} 个岗位匹配度>=60", scoredJobs.size());

        if (scoredJobs.isEmpty()) {
            throw new RuntimeException("未找到足够匹配的岗位（匹配度均低于60分），建议优化简历或调整求职方向");
        }

        // 取匹配度最高的岗位
        Map.Entry<JobListing, Integer> bestMatch = scoredJobs.entrySet().iterator().next();
        JobListing targetJob = bestMatch.getKey();
        int matchScore = bestMatch.getValue();
        log.info("[成员7] 最佳匹配: {} @ {} | 匹配度: {}分", targetJob.getTitle(), targetJob.getCompany(), matchScore);

        // ═══ Step4: 生成简历优化建议 ═══
        log.info("[成员7] Step4: 生成简历优化建议");
        String optimizationTip = resumeOptimizer.generateOptimizationTip(profile, targetJob);
        log.info("[成员7] 优化建议生成完成: {} 字符", optimizationTip.length());

        // ═══ Step5: 检查是否已投递 ═══
        boolean hasApplied = jobSearchClient.hasApplied(targetJob.getJobId(), userId);
        if (hasApplied) {
            log.info("[成员7] 用户已投递过该岗位，跳过投递");
            return ApplicationResult.builder()
                    .success(false)
                    .jobListing(targetJob)
                    .matchScore(matchScore)
                    .optimizationTip(optimizationTip)
                    .message("您已投递过该岗位，无需重复投递")
                    .build();
        }

        // ═══ Step6: 执行投递 ═══
        log.info("[成员7] Step6: 执行投递");
        ApplicationResult result = applicationClient.apply(targetJob, profile);
        result.setMatchScore(matchScore);
        result.setOptimizationTip(optimizationTip);

        if (result.isSuccess()) {
            // ═══ Step7: 记录投递 ═══
            log.info("[成员7] Step7: 记录投递");
            result.setAppliedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            applicationTracker.record(result, userId);
            log.info("[成员7] 投递记录已保存");
        }

        log.info("[成员7] autoApply 完成 | 成功: {} | 岗位: {} @ {}",
                result.isSuccess(), targetJob.getTitle(), targetJob.getCompany());
        return result;
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
        Map<JobListing, Integer> scoredJobs = matchScorer.scoreAndRank(profile, jobs, 60);
        if (scoredJobs.isEmpty()) {
            return "❌ 未找到足够匹配的岗位（匹配度均低于60分）";
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
    // 方法4: 查询投递进度
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
