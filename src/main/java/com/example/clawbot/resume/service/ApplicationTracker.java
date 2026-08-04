package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.ApplicationRecord;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** 用 SQLite 占用岗位并保存结果，避免同一用户重复投递。 */
@Service
@RequiredArgsConstructor
public class ApplicationTracker {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ApplicationRecordRepository repository;

    @Transactional
    public String start(JobListing job, String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户ID不能为空");
        if (job == null || job.getJobId() == null || job.getJobId().isBlank()) {
            throw new IllegalArgumentException("岗位ID不能为空");
        }

        String uid = userId.trim();
        String now = OffsetDateTime.now(ZONE).toString();
        var existing = repository.findByUserIdAndJobId(uid, job.getJobId());
        if (existing.isPresent()) {
            ApplicationRecord record = existing.get();
            return repository.restartFailed(record.getRecordId(), now) ? record.getRecordId() : null;
        }

        String recordId = UUID.nameUUIDFromBytes(
                (uid + "\0" + job.getJobId()).getBytes(StandardCharsets.UTF_8)).toString();
        ApplicationRecord record = ApplicationRecord.builder()
                .recordId(recordId)
                .userId(uid)
                .jobId(job.getJobId())
                .platform(job.getPlatform())
                .jobTitle(job.getTitle())
                .company(job.getCompany())
                .salary(job.getSalary())
                .city(job.getCity())
                .status("PROCESSING")
                .appliedAt(now)
                .updatedAt(now)
                .remark("正在投递")
                .build();
        return repository.insertIfAbsent(record) ? recordId : null;
    }

    @Transactional
    public void finish(String recordId, ApplicationResult result) {
        String now = OffsetDateTime.now(ZONE).toString();
        String status = result.isSuccess() ? "SUBMITTED"
                : "UNKNOWN".equals(result.getStatus()) ? "UNKNOWN" : "FAILED";
        repository.complete(
                recordId,
                result.getApplicationId(),
                status,
                result.getAppliedAt() == null ? now : result.getAppliedAt(),
                now,
                result.getMessage());
    }

    @Transactional(readOnly = true)
    public List<ApplicationRecord> getRecords(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户ID不能为空");
        return repository.findByUserId(userId.trim());
    }
}
