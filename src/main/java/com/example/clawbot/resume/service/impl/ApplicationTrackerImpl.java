package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.ApplicationRecord;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import com.example.clawbot.resume.service.ApplicationTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationTrackerImpl implements ApplicationTracker {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "SUBMITTED", "VIEWED", "INTERVIEW", "REJECTED", "OFFER", "FAILED"
    );

    private final ApplicationRecordRepository repository;

    @Override
    @Transactional
    public ApplicationRecord record(ApplicationResult result, String userId) {
        validateUserId(userId);
        if (result == null || result.getJobListing() == null) {
            throw new IllegalArgumentException("投递结果和岗位信息不能为空");
        }

        JobListing job = result.getJobListing();
        if (isBlank(job.getTitle())) {
            throw new IllegalArgumentException("岗位名称不能为空");
        }

        String now = OffsetDateTime.now(ZONE).toString();
        String status = resolveStatus(result);
        ApplicationRecord record = ApplicationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .userId(userId.trim())
                .jobTitle(job.getTitle())
                .company(job.getCompany())
                .salary(job.getSalary())
                .city(job.getCity())
                .status(status)
                .matchScore(result.getMatchScore())
                .appliedAt(isBlank(result.getAppliedAt()) ? now : result.getAppliedAt())
                .updatedAt(now)
                .remark(result.getMessage())
                .build();

        repository.insert(record);
        log.info("投递记录已保存: recordId={}, userId={}, status={}",
                record.getRecordId(), record.getUserId(), record.getStatus());
        return record;
    }

    @Override
    @Transactional
    public int recordBatch(List<ApplicationResult> results, String userId) {
        validateUserId(userId);
        if (results == null || results.isEmpty()) {
            return 0;
        }
        results.forEach(result -> record(result, userId));
        return results.size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApplicationRecord> getRecords(String userId) {
        validateUserId(userId);
        return repository.findByUserId(userId.trim());
    }

    @Override
    @Transactional
    public ApplicationRecord updateStatus(String recordId, String newStatus) {
        if (isBlank(recordId)) {
            throw new IllegalArgumentException("投递记录ID不能为空");
        }
        String status = normalizeStatus(newStatus);
        String updatedAt = OffsetDateTime.now(ZONE).toString();
        if (repository.updateStatus(recordId.trim(), status, updatedAt) == 0) {
            throw new IllegalArgumentException("投递记录不存在: " + recordId);
        }
        return repository.findById(recordId.trim())
                .orElseThrow(() -> new IllegalStateException("投递状态已更新，但读取记录失败"));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> getStatistics(String userId) {
        List<ApplicationRecord> records = getRecords(userId);
        Map<String, Integer> statistics = new LinkedHashMap<>();
        statistics.put("total", records.size());
        statistics.put("submitted", count(records, "SUBMITTED"));
        statistics.put("viewed", count(records, "VIEWED"));
        statistics.put("interview", count(records, "INTERVIEW"));
        statistics.put("rejected", count(records, "REJECTED"));
        statistics.put("offer", count(records, "OFFER"));
        statistics.put("failed", count(records, "FAILED"));
        return statistics;
    }

    private int count(List<ApplicationRecord> records, String status) {
        return (int) records.stream()
                .filter(record -> status.equalsIgnoreCase(record.getStatus()))
                .count();
    }

    private String resolveStatus(ApplicationResult result) {
        if (isBlank(result.getStatus())) {
            return result.isSuccess() ? "SUBMITTED" : "FAILED";
        }
        return normalizeStatus(result.getStatus());
    }

    private String normalizeStatus(String status) {
        if (isBlank(status)) {
            throw new IllegalArgumentException("投递状态不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的投递状态: " + status);
        }
        return normalized;
    }

    private void validateUserId(String userId) {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
