package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.ApplicationRecord;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import com.example.clawbot.resume.service.impl.ApplicationTrackerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationTrackerImplTest {

    private final List<ApplicationRecord> records = new ArrayList<>();
    private ApplicationTracker tracker;

    @BeforeEach
    void setUp() {
        records.clear();
        ApplicationRecordRepository repository = mock(ApplicationRecordRepository.class);
        tracker = new ApplicationTrackerImpl(repository);

        doAnswer(invocation -> {
            records.add(invocation.getArgument(0));
            return null;
        }).when(repository).insert(any(ApplicationRecord.class));

        when(repository.findByUserId(anyString())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(0);
            return records.stream()
                    .filter(record -> userId.equals(record.getUserId()))
                    .toList();
        });

        when(repository.updateStatus(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String recordId = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            String updatedAt = invocation.getArgument(2);
            return records.stream()
                    .filter(record -> recordId.equals(record.getRecordId()))
                    .findFirst()
                    .map(record -> {
                        record.setStatus(status);
                        record.setUpdatedAt(updatedAt);
                        return 1;
                    })
                    .orElse(0);
        });

        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String recordId = invocation.getArgument(0);
            return records.stream()
                    .filter(record -> recordId.equals(record.getRecordId()))
                    .findFirst();
        });
    }

    @Test
    void shouldSaveAndQueryApplicationRecord() {
        ApplicationRecord saved = tracker.record(result(true, null, "Java开发"), "user-1");

        assertThat(saved.getRecordId()).isNotBlank();
        assertThat(saved.getStatus()).isEqualTo("SUBMITTED");
        assertThat(saved.getJobTitle()).isEqualTo("Java开发");
        assertThat(tracker.getRecords("user-1")).containsExactly(saved);
    }

    @Test
    void shouldCalculateStatistics() {
        tracker.record(result(true, "SUBMITTED", "Java开发"), "user-1");
        tracker.record(result(true, "VIEWED", "后端开发"), "user-1");
        tracker.record(result(true, "INTERVIEW", "平台开发"), "user-1");
        tracker.record(result(false, null, "服务端开发"), "user-1");

        Map<String, Integer> statistics = tracker.getStatistics("user-1");
        assertThat(statistics).containsEntry("total", 4);
        assertThat(statistics).containsEntry("submitted", 1);
        assertThat(statistics).containsEntry("viewed", 1);
        assertThat(statistics).containsEntry("interview", 1);
        assertThat(statistics).containsEntry("failed", 1);
    }

    @Test
    void shouldUpdateStatus() {
        ApplicationRecord saved = tracker.record(result(true, "SUBMITTED", "Java开发"), "user-1");

        ApplicationRecord updated = tracker.updateStatus(saved.getRecordId(), "offer");

        assertThat(updated.getStatus()).isEqualTo("OFFER");
    }

    @Test
    void shouldRejectUnknownStatusAndMissingRecord() {
        assertThatThrownBy(() -> tracker.updateStatus("missing", "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");

        assertThatThrownBy(() -> tracker.updateStatus("missing", "VIEWED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    private ApplicationResult result(boolean success, String status, String title) {
        return ApplicationResult.builder()
                .success(success)
                .status(status)
                .message(success ? "投递成功" : "投递失败")
                .matchScore(85)
                .jobListing(JobListing.builder()
                        .jobId("job-" + title)
                        .title(title)
                        .company("示例公司")
                        .salary("20k-30k")
                        .city("北京")
                        .build())
                .build();
    }
}
