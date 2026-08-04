package com.example.clawbot.resume.repository;

import com.example.clawbot.resume.model.ApplicationRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationRecordRepositoryTest {

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;
    private ApplicationRecordRepository repository;

    @BeforeEach
    void setUp() {
        String url = "jdbc:sqlite:" + tempDir.resolve("applications.db");
        dataSource = new SingleConnectionDataSource(url, true);
        repository = new ApplicationRecordRepository(new JdbcTemplate(dataSource));
        repository.createTable();
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void shouldReserveQueryAndCompleteRecord() {
        ApplicationRecord record = ApplicationRecord.builder()
                .recordId("record-1")
                .userId("user-1")
                .jobId("84541619")
                .applicationId("LP-1001")
                .platform("liepin")
                .jobTitle("Java开发")
                .company("示例公司")
                .salary("20k-30k")
                .city("北京")
                .status("PROCESSING")
                .appliedAt("2026-07-30T10:00:00+08:00")
                .updatedAt("2026-07-30T10:00:00+08:00")
                .remark("投递成功")
                .build();

        assertThat(repository.insertIfAbsent(record)).isTrue();
        assertThat(repository.insertIfAbsent(record)).isFalse();

        assertThat(repository.findByUserId("user-1"))
                .singleElement()
                .usingRecursiveComparison()
                .isEqualTo(record);
        assertThat(repository.findByUserIdAndJobId("user-1", "84541619")).isPresent();

        repository.complete(
                "record-1", "LP-1001", "SUBMITTED",
                "2026-07-30T10:00:00+08:00",
                "2026-07-31T10:00:00+08:00", "投递成功");
        assertThat(repository.findByUserIdAndJobId("user-1", "84541619"))
                .get().extracting(ApplicationRecord::getStatus).isEqualTo("SUBMITTED");
    }
}
