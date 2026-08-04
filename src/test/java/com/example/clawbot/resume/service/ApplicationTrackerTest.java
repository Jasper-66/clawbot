package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTrackerTest {

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;
    private ApplicationTracker tracker;
    private JobListing job;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite:" + tempDir.resolve("tracker.db"), true);
        ApplicationRecordRepository repository = new ApplicationRecordRepository(new JdbcTemplate(dataSource));
        repository.createTable();
        tracker = new ApplicationTracker(repository);
        job = JobListing.builder()
                .jobId("job-1").jobKind("2").platform("liepin")
                .title("Java开发").company("示例公司")
                .salary("15-20K").city("杭州").build();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void shouldBlockDuplicateAndSaveSuccess() {
        String recordId = tracker.start(job, "user-1");

        assertThat(recordId).isNotBlank();
        assertThat(tracker.start(job, "user-1")).isNull();

        tracker.finish(recordId, result(true, "SUBMITTED", "投递成功"));
        assertThat(tracker.getRecords("user-1")).singleElement()
                .extracting(record -> record.getStatus()).isEqualTo("SUBMITTED");
        assertThat(tracker.start(job, "user-1")).isNull();
    }

    @Test
    void failedApplicationCanRetryButUnknownResultCannot() {
        String failedId = tracker.start(job, "user-1");
        tracker.finish(failedId, result(false, "FAILED", "简历不完整"));
        assertThat(tracker.start(job, "user-1")).isEqualTo(failedId);

        tracker.finish(failedId, result(false, "UNKNOWN", "请求超时"));
        assertThat(tracker.start(job, "user-1")).isNull();
    }

    private ApplicationResult result(boolean success, String status, String message) {
        return ApplicationResult.builder()
                .success(success).status(status).message(message).jobListing(job).build();
    }
}
