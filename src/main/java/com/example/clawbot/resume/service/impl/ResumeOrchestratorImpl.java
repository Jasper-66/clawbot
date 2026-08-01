package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.model.*;
import com.example.clawbot.resume.repository.ApplicationSessionRepository;
import com.example.clawbot.resume.repository.ApplicationSessionRepository.PendingApplication;
import com.example.clawbot.resume.repository.ApplicationSessionRepository.PendingJob;
import com.example.clawbot.resume.repository.ApplicationSessionRepository.RecentSearch;
import com.example.clawbot.resume.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 串联简历、岗位匹配、投递和记录保存。
@Slf4j
@Service
public class ResumeOrchestratorImpl implements ResumeOrchestrator {

    private static final long PENDING_TTL_MILLIS = 15 * 60 * 1000L;
    private static final long SEARCH_TTL_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_DISPLAYED_JOBS = 5;
    private static final Pattern INDEX_PATTERN = Pattern.compile("\\d+");

    private final ResumeParser resumeParser;
    private final JobSearchClient jobSearchClient;
    private final MatchScorer matchScorer;
    private final ApplicationClient applicationClient;
    private final ApplicationTracker applicationTracker;
    private final ApplicationSessionRepository applicationSessionRepository;

    public ResumeOrchestratorImpl(
            ResumeParser resumeParser,
            JobSearchClient jobSearchClient,
            MatchScorer matchScorer,
            ApplicationClient applicationClient,
            ApplicationTracker applicationTracker,
            ApplicationSessionRepository applicationSessionRepository) {
        this.resumeParser = resumeParser;
        this.jobSearchClient = jobSearchClient;
        this.matchScorer = matchScorer;
        this.applicationClient = applicationClient;
        this.applicationTracker = applicationTracker;
        this.applicationSessionRepository = applicationSessionRepository;
    }

    @Override
    public String searchJobs(String userId, String keyword, String city) {
        log.info("搜索岗位 | 用户: {} | 关键词: {} | 城市: {}", userId, keyword, city);

        UserProfile profile = resumeParser.getProfile(userId);
        String experience = profile != null && profile.getExperienceYears() != null
                ? profile.getExperienceYears() + "年" : "不限";
        String salary = profile != null ? profile.getSalaryRange() : "不限";

        List<JobListing> jobs = jobSearchClient.searchJobs(keyword, city, experience, salary);
        log.info("搜索到 {} 个岗位", jobs.size());

        if (jobs.isEmpty()) {
            applicationSessionRepository.deleteRecentSearch(userId);
            return "🔍 当前条件未找到匹配岗位，请调整搜索条件后重试。\n\n💡 建议：\n1. 尝试更宽泛的关键词\n2. 扩大搜索城市范围\n3. 调整薪资期望";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 为您找到以下岗位：\n\n");

        int limit = Math.min(MAX_DISPLAYED_JOBS, jobs.size());
        List<JobListing> displayedJobs = List.copyOf(jobs.subList(0, limit));
        applicationSessionRepository.saveRecentSearch(
                userId,
                keyword,
                city,
                displayedJobs,
                System.currentTimeMillis()
        );
        for (int i = 0; i < limit; i++) {
            JobListing job = displayedJobs.get(i);
            sb.append(String.format("%d. %s @ %s\n", i + 1, job.getTitle(), job.getCompany()));
            sb.append(String.format("   💰 %s | 📍 %s\n", job.getSalary(), job.getCity()));
            if (job.getExperienceRequired() != null) {
                sb.append(String.format("   📝 经验要求: %s\n", job.getExperienceRequired()));
            }
            sb.append("\n");
        }

        if (jobs.size() > MAX_DISPLAYED_JOBS) {
            sb.append(String.format("... 还有 %d 个岗位未展示\n", jobs.size() - MAX_DISPLAYED_JOBS));
        }

        sb.append("\n💡 回复“投1号”“投1和3号”或“全部投递”即可生成待确认清单。");

        log.info("岗位搜索完成，返回 {} 字符", sb.length());
        return sb.toString();
    }

    @Override
    public String prepareApplication(String userId, String userMessage) {
        UserProfile profile = resumeParser.getProfile(userId);

        if (isRecentSelectionRequest(userMessage)) {
            RecentSearch recentSearch = getValidRecentSearch(userId);
            if (recentSearch == null) {
                return "没有找到最近15分钟内的岗位列表，请先搜索岗位，再回复“投1号”或“全部投递”。";
            }
            if (profile == null) {
                profile = minimalProfile(userId, recentSearch);
            }
            List<JobListing> selectedJobs = selectJobs(recentSearch.jobs(), userMessage);
            return createPendingApplication(userId, profile, selectedJobs);
        }

        if (profile == null) {
            profile = resumeParser.parseFromMessage(userId, userMessage);
        }

        String keyword = hasText(profile.getDesiredPosition())
                ? profile.getDesiredPosition()
                : extractKeyword(userMessage);
        String city = hasText(profile.getDesiredCity())
                ? profile.getDesiredCity()
                : extractCity(userMessage);
        String experience = profile.getExperienceYears() != null
                ? profile.getExperienceYears() + "年"
                : "不限";
        String salary = profile.getSalaryRange() != null
                ? profile.getSalaryRange()
                : "不限";

        List<JobListing> jobs = jobSearchClient.searchJobs(keyword, city, experience, salary);
        if (jobs.isEmpty()) {
            return "未找到匹配岗位，请调整岗位关键词或城市后重试。";
        }

        Map<JobListing, Integer> scoredJobs = matchScorer.scoreAndRank(profile, jobs);

        Map.Entry<JobListing, Integer> bestMatch = scoredJobs.entrySet().iterator().next();
        JobListing job = bestMatch.getKey();
        int matchScore = bestMatch.getValue();

        if (jobSearchClient.hasApplied(job.getJobId(), userId)) {
            return String.format("您已经投递过 %s @ %s，请选择其他岗位。",
                    job.getTitle(), job.getCompany());
        }

        String pendingId = UUID.randomUUID().toString();
        List<PendingJob> pendingJobs = List.of(new PendingJob(job, matchScore));
        applicationSessionRepository.savePendingApplication(
                new PendingApplication(
                        pendingId,
                        userId,
                        profile,
                        pendingJobs,
                        System.currentTimeMillis()
                )
        );

        return formatPendingApplication(pendingJobs);
    }

    @Override
    public String confirmApplication(String userId) {
        PendingApplication pending = applicationSessionRepository
                .findLatestPendingApplication(userId)
                .orElse(null);
        if (pending == null) {
            return "❌ 未找到待确认任务，请重新搜索岗位。";
        }
        if (System.currentTimeMillis() - pending.createdAt() > PENDING_TTL_MILLIS) {
            applicationSessionRepository.deletePendingApplication(pending.pendingId());
            return "❌ 待确认任务已超过15分钟，请重新搜索岗位。";
        }

        List<ApplicationResult> results = new ArrayList<>();
        for (PendingJob pendingJob : pending.jobs()) {
            JobListing job = pendingJob.job();
            if (jobSearchClient.hasApplied(job.getJobId(), userId)) {
                results.add(failedApplication(job, "您已经投递过该岗位"));
                continue;
            }

            ApplicationResult result = applicationClient.apply(job, pending.profile());
            result.setMatchScore(pendingJob.matchScore());
            if (result.isSuccess()) {
                result.setAppliedAt(LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                applicationTracker.record(result, userId);
            }
            results.add(result);
        }
        if (results.stream().allMatch(ApplicationResult::isSuccess)) {
            applicationSessionRepository.deletePendingApplication(pending.pendingId());
        }
        return formatApplicationResults(results);
    }

    /** 查询投递统计和最近记录。 */
    @Override
    public String getApplicationProgress(String userId) {
        log.info("查询投递进度 | 用户: {}", userId);

        Map<String, Integer> stats = applicationTracker.getStatistics(userId);
        int total = stats.getOrDefault("total", 0);
        int viewed = stats.getOrDefault("viewed", 0);
        int interview = stats.getOrDefault("interview", 0);
        int rejected = stats.getOrDefault("rejected", 0);
        int offer = stats.getOrDefault("offer", 0);
        int pending = total - viewed - interview - rejected - offer;

        List<ApplicationRecord> records = applicationTracker.getRecords(userId);

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

        log.info("投递进度查询完成");
        return sb.toString();
    }

    private String createPendingApplication(
            String userId,
            UserProfile profile,
            List<JobListing> selectedJobs
    ) {
        List<PendingJob> pendingJobs = new ArrayList<>();
        for (JobListing job : selectedJobs) {
            if (jobSearchClient.hasApplied(job.getJobId(), userId)) {
                continue;
            }
            int matchScore = matchScorer.score(profile, job);
            pendingJobs.add(new PendingJob(job, matchScore));
        }

        if (pendingJobs.isEmpty()) {
            return "所选岗位均已投递过，请重新选择其他岗位。";
        }

        String pendingId = UUID.randomUUID().toString();
        applicationSessionRepository.savePendingApplication(
                new PendingApplication(
                        pendingId,
                        userId,
                        profile,
                        List.copyOf(pendingJobs),
                        System.currentTimeMillis()
                )
        );
        return formatPendingApplication(pendingJobs);
    }

    private String formatPendingApplication(List<PendingJob> pendingJobs) {
        StringBuilder result = new StringBuilder();
        result.append(String.format("请确认是否投递以下%d个岗位：\n\n", pendingJobs.size()));
        for (int index = 0; index < pendingJobs.size(); index++) {
            PendingJob pendingJob = pendingJobs.get(index);
            JobListing job = pendingJob.job();
            result.append(String.format(
                    "%d. %s @ %s\n   💰 %s | 📍 %s | 📊 %d分\n",
                    index + 1,
                    job.getTitle(),
                    job.getCompany(),
                    job.getSalary(),
                    job.getCity(),
                    pendingJob.matchScore()
            ));
        }
        result.append("\n回复“确认投递”后才会真正提交，15分钟内有效。");
        return result.toString();
    }

    private String formatApplicationResults(List<ApplicationResult> results) {
        if (results.size() == 1) {
            ApplicationResult result = results.get(0);
            JobListing job = result.getJobListing();
            if (!result.isSuccess()) {
                return String.format(
                        "❌ 投递失败：%s @ %s\n原因：%s",
                        job.getTitle(),
                        job.getCompany(),
                        result.getMessage()
                );
            }
            return String.format(
                    "✅ 投递成功！\n📋 %s @ %s\n💰 %s\n📍 %s\n📊 匹配度：%d分%s",
                    job.getTitle(),
                    job.getCompany(),
                    job.getSalary(),
                    job.getCity(),
                    result.getMatchScore(),
                    hasText(result.getApplicationId())
                            ? "\n🎫 投递编号：" + result.getApplicationId()
                            : ""
            );
        }

        long successCount = results.stream().filter(ApplicationResult::isSuccess).count();
        StringBuilder summary = new StringBuilder(String.format(
                "📊 批量投递完成：成功 %d 个，失败 %d 个\n\n",
                successCount,
                results.size() - successCount
        ));
        for (int index = 0; index < results.size(); index++) {
            ApplicationResult result = results.get(index);
            JobListing job = result.getJobListing();
            summary.append(String.format(
                    "%s %d. %s @ %s%s\n",
                    result.isSuccess() ? "✅" : "❌",
                    index + 1,
                    job.getTitle(),
                    job.getCompany(),
                    result.isSuccess() ? "" : "：" + result.getMessage()
            ));
        }
        return summary.toString().trim();
    }

    private RecentSearch getValidRecentSearch(String userId) {
        RecentSearch recentSearch = applicationSessionRepository
                .findRecentSearch(userId)
                .orElse(null);
        if (recentSearch == null) {
            return null;
        }
        if (System.currentTimeMillis() - recentSearch.createdAt() > SEARCH_TTL_MILLIS) {
            applicationSessionRepository.deleteRecentSearch(userId);
            return null;
        }
        return recentSearch;
    }

    private boolean isRecentSelectionRequest(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", "");
        if (isAllSelection(normalized)) {
            return true;
        }
        boolean wantsToApply = normalized.contains("投") || normalized.contains("申请");
        if (!wantsToApply) {
            return false;
        }
        return INDEX_PATTERN.matcher(normalized).find()
                || normalized.contains("第")
                || normalized.matches(".*[一二三四五六七八九十]号.*");
    }

    private List<JobListing> selectJobs(List<JobListing> jobs, String message) {
        String normalized = message.replaceAll("\\s+", "");
        if (isAllSelection(normalized)) {
            return jobs;
        }

        Set<Integer> indexes = new LinkedHashSet<>();
        Matcher matcher = INDEX_PATTERN.matcher(normalized);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group()));
        }

        String[] chineseNumbers = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int index = 0; index < chineseNumbers.length; index++) {
            String number = chineseNumbers[index];
            if (normalized.contains("第" + number)
                    || normalized.contains(number + "号")) {
                indexes.add(index + 1);
            }
        }

        if (indexes.isEmpty()) {
            throw new IllegalArgumentException("请说明要投递的岗位编号，例如“投1号”或“全部投递”");
        }

        List<JobListing> selected = new ArrayList<>();
        for (Integer index : indexes) {
            if (index < 1 || index > jobs.size()) {
                throw new IllegalArgumentException(
                        "岗位编号" + index + "不存在，当前列表共有" + jobs.size() + "个岗位"
                );
            }
            selected.add(jobs.get(index - 1));
        }
        return selected;
    }

    private boolean isAllSelection(String message) {
        return message.contains("全部")
                || message.contains("所有")
                || message.contains("都投")
                || message.contains("全投");
    }

    private UserProfile minimalProfile(String userId, RecentSearch search) {
        return UserProfile.builder()
                .userId(userId)
                .name("")
                .desiredPosition(search.keyword())
                .desiredCity(search.city())
                .salaryRange("")
                .experienceYears(0)
                .education("")
                .skills(new ArrayList<>())
                .summary("")
                .workHistory(new ArrayList<>())
                .projectHistory(new ArrayList<>())
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

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

    private ApplicationResult failedApplication(JobListing job, String message) {
        return ApplicationResult.builder()
                .success(false)
                .jobListing(job)
                .status("FAILED")
                .message(message)
                .build();
    }
}
