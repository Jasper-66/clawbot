package com.example.clawbot.resume.service;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.model.ApplicationRecord;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationSessionRepository;
import com.example.clawbot.resume.repository.ApplicationSessionRepository.RecentSearch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 串联岗位搜索、直接投递和记录查询。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeOrchestrator {

    private static final long SEARCH_TTL_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_JOBS = 5;
    private static final Pattern INDEX_PATTERN = Pattern.compile("(?<!\\d)([1-5])(?!\\d)");

    private final JobSearchClient jobSearchClient;
    private final ApplicationClient applicationClient;
    private final ApplicationTracker applicationTracker;
    private final ApplicationSessionRepository sessionRepository;

    public String searchJobs(String userId, String keyword, String city) {
        log.info("搜索岗位 | 用户: {} | 关键词: {} | 城市: {}", userId, keyword, city);
        List<JobListing> jobs = jobSearchClient.searchJobs(keyword, city);
        if (jobs.isEmpty()) {
            sessionRepository.deleteRecentSearch(userId);
            return "🔍 当前条件未找到岗位，请换一个关键词或城市重试。";
        }

        List<JobListing> displayed = List.copyOf(jobs.subList(0, Math.min(MAX_JOBS, jobs.size())));
        sessionRepository.saveRecentSearch(
                userId, keyword, city, displayed, System.currentTimeMillis());

        StringBuilder reply = new StringBuilder("📋 为您找到以下岗位：\n\n");
        for (int i = 0; i < displayed.size(); i++) {
            JobListing job = displayed.get(i);
            reply.append(String.format("%d. %s @ %s\n   💰 %s | 📍 %s\n",
                    i + 1, job.getTitle(), job.getCompany(), job.getSalary(), job.getCity()));
            if (hasText(job.getExperienceRequired())) {
                reply.append("   📝 经验要求: ").append(job.getExperienceRequired()).append("\n");
            }
            reply.append("\n");
        }
        return reply.append("💡 回复“投1号”“投1和3号”或“全部投递”即可直接投递。").toString();
    }

    public String applyJobs(String userId, String selection) {
        if (!isSelectionRequest(selection)) {
            return "请明确回复“投1号”“投1和3号”或“全部投递”。";
        }

        RecentSearch recent = validRecentSearch(userId);
        if (recent == null) {
            return "没有找到最近15分钟内的岗位列表，请先搜索岗位。";
        }
        return applySelectedJobs(userId, selectJobs(recent.jobs(), selection));
    }

    public String getApplicationProgress(String userId) {
        List<ApplicationRecord> records = applicationTracker.getRecords(userId);
        if (records.isEmpty()) return "暂无投递记录，请先搜索岗位。";

        long submitted = count(records, "SUBMITTED");
        long failed = count(records, "FAILED");
        long uncertain = count(records, "UNKNOWN");
        long processing = count(records, "PROCESSING");
        StringBuilder reply = new StringBuilder(String.format(
                "📊 投递记录：成功 %d，失败 %d，待确认 %d，处理中 %d\n\n",
                submitted, failed, uncertain, processing));
        for (int i = 0; i < Math.min(5, records.size()); i++) {
            ApplicationRecord record = records.get(i);
            reply.append(String.format("%d. %s %s @ %s\n",
                    i + 1, statusEmoji(record.getStatus()), record.getJobTitle(), record.getCompany()));
        }
        return reply.toString().trim();
    }

    private String applySelectedJobs(String userId, List<JobListing> jobs) {
        List<ApplicationResult> results = new ArrayList<>();
        for (JobListing job : jobs) {
            String recordId = applicationTracker.start(job, userId);
            if (recordId == null) {
                results.add(failed(job, "该岗位已经投递或正在处理"));
                continue;
            }
            ApplicationResult result = applicationClient.apply(job);
            applicationTracker.finish(recordId, result);
            results.add(result);
        }
        return formatResults(results);
    }

    private String formatResults(List<ApplicationResult> results) {
        if (results.size() == 1) {
            ApplicationResult result = results.get(0);
            JobListing job = result.getJobListing();
            if (!result.isSuccess()) {
                if ("UNKNOWN".equals(result.getStatus())) {
                    return String.format("❓ 投递结果待确认：%s @ %s\n%s\n请勿重复投递。",
                            job.getTitle(), job.getCompany(), result.getMessage());
                }
                return String.format("❌ 投递未成功：%s @ %s\n原因：%s",
                        job.getTitle(), job.getCompany(), result.getMessage());
            }
            return String.format("✅ 投递成功！\n📋 %s @ %s\n💰 %s\n📍 %s%s",
                    job.getTitle(), job.getCompany(), job.getSalary(), job.getCity(),
                    hasText(result.getApplicationId()) ? "\n🎫 投递编号：" + result.getApplicationId() : "");
        }

        long success = results.stream().filter(ApplicationResult::isSuccess).count();
        long uncertain = results.stream().filter(result -> "UNKNOWN".equals(result.getStatus())).count();
        StringBuilder reply = new StringBuilder(String.format(
                "📊 批量投递完成：成功 %d 个，失败 %d 个，待确认 %d 个\n\n",
                success, results.size() - success - uncertain, uncertain));
        for (int i = 0; i < results.size(); i++) {
            ApplicationResult result = results.get(i);
            reply.append(String.format("%s %d. %s @ %s%s\n",
                    result.isSuccess() ? "✅" : "UNKNOWN".equals(result.getStatus()) ? "❓" : "❌", i + 1,
                    result.getJobListing().getTitle(), result.getJobListing().getCompany(),
                    result.isSuccess() ? "" : "：" + result.getMessage()));
        }
        return reply.toString().trim();
    }

    private RecentSearch validRecentSearch(String userId) {
        RecentSearch recent = sessionRepository.findRecentSearch(userId).orElse(null);
        if (recent != null && System.currentTimeMillis() - recent.createdAt() > SEARCH_TTL_MILLIS) {
            sessionRepository.deleteRecentSearch(userId);
            return null;
        }
        return recent;
    }

    private boolean isSelectionRequest(String message) {
        String text = normalize(message);
        if (isAllSelection(text)) return true;
        boolean wantsToApply = text.contains("投") || text.contains("申请");
        return wantsToApply && (INDEX_PATTERN.matcher(text).find()
                || text.matches(".*(第[一二三四五]|[一二三四五]号).*"));
    }

    private List<JobListing> selectJobs(List<JobListing> jobs, String message) {
        String text = normalize(message);
        if (isAllSelection(text)) return jobs;

        Set<Integer> indexes = new LinkedHashSet<>();
        Matcher matcher = INDEX_PATTERN.matcher(text);
        while (matcher.find()) indexes.add(Integer.parseInt(matcher.group(1)));

        String[] chinese = {"一", "二", "三", "四", "五"};
        for (int i = 0; i < chinese.length; i++) {
            if (text.contains("第" + chinese[i]) || text.contains(chinese[i] + "号")) {
                indexes.add(i + 1);
            }
        }
        if (indexes.isEmpty()) throw new IllegalArgumentException("请提供岗位编号");

        List<JobListing> selected = new ArrayList<>();
        for (int index : indexes) {
            if (index > jobs.size()) {
                throw new IllegalArgumentException("岗位编号" + index + "不存在，当前共" + jobs.size() + "个岗位");
            }
            selected.add(jobs.get(index - 1));
        }
        return selected;
    }

    private boolean isAllSelection(String text) {
        boolean all = text.contains("全部") || text.contains("所有")
                || text.contains("都投") || text.contains("全投");
        return all && (text.contains("投") || text.contains("申请"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private long count(List<ApplicationRecord> records, String status) {
        return records.stream().filter(record -> status.equals(record.getStatus())).count();
    }

    private String statusEmoji(String status) {
        return switch (status == null ? "" : status) {
            case "SUBMITTED" -> "✅";
            case "FAILED" -> "❌";
            case "UNKNOWN" -> "❓";
            default -> "⏳";
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ApplicationResult failed(JobListing job, String message) {
        return ApplicationResult.builder()
                .success(false).jobListing(job).status("FAILED").message(message).build();
    }
}
