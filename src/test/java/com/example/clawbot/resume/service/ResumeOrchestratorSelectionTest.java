package com.example.clawbot.resume.service;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResumeOrchestratorSelectionTest {

    @TempDir
    Path tempDir;

    private final JobSearchClient jobSearchClient = mock(JobSearchClient.class);
    private final ApplicationClient applicationClient = mock(ApplicationClient.class);
    private final ApplicationTracker applicationTracker = mock(ApplicationTracker.class);

    private ResumeOrchestrator orchestrator;
    private ApplicationSessionRepository applicationSessionRepository;
    private SingleConnectionDataSource dataSource;
    private JobListing firstJob;
    private JobListing secondJob;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite:" + tempDir.resolve("application-sessions.db"),
                true
        );
        applicationSessionRepository = new ApplicationSessionRepository(
                new JdbcTemplate(dataSource),
                new ObjectMapper()
        );
        applicationSessionRepository.createTable();
        orchestrator = newOrchestrator();
        firstJob = job("1001", "外卖站长", "甲公司");
        secondJob = job("1002", "配送员", "乙公司");

        when(jobSearchClient.searchJobs("外卖", "杭州"))
                .thenReturn(List.of(firstJob, secondJob));
        when(applicationTracker.start(any(), anyString())).thenReturn("record-1");
        when(applicationClient.apply(any())).thenAnswer(invocation -> {
            JobListing job = invocation.getArgument(0);
            return ApplicationResult.builder()
                    .success(true)
                    .jobListing(job)
                    .applicationId("LP-" + job.getJobId())
                    .status("SUBMITTED")
                    .message("投递成功")
                    .build();
        });
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void allSelectionShouldReuseRecentSearchAndApplyImmediately() {
        orchestrator.searchJobs("user-1", "外卖", "杭州");

        orchestrator = newOrchestrator();

        String result = orchestrator.applyJobs("user-1", "全部投递");

        assertTrue(result.contains("成功 2 个"));
        assertTrue(result.contains("外卖站长"));
        assertTrue(result.contains("配送员"));
        verify(jobSearchClient, times(1))
                .searchJobs("外卖", "杭州");
        verify(applicationClient, times(2)).apply(any());
        verify(applicationTracker, times(2)).start(any(), anyString());
        verify(applicationTracker, times(2)).finish(anyString(), any());
    }

    @Test
    void numberedSelectionShouldApplyOnlySelectedJob() {
        orchestrator.searchJobs("user-1", "外卖", "杭州");

        String result = orchestrator.applyJobs("user-1", "投2号");

        assertFalse(result.contains("外卖站长"));
        assertTrue(result.contains("配送员"));
        verify(applicationClient).apply(secondJob);
    }

    @Test
    void unrelatedNumbersShouldNotBecomeJobIndexes() {
        orchestrator.searchJobs("user-1", "外卖", "杭州");

        String result = orchestrator.applyJobs("user-1", "投1号，薪资希望15k");

        assertTrue(result.contains("外卖站长"));
        verify(applicationClient).apply(firstJob);
        verify(applicationClient, never()).apply(secondJob);
    }

    @Test
    void failedApplicationShouldReturnPlatformReason() {
        orchestrator.searchJobs("user-1", "外卖", "杭州");
        doReturn(ApplicationResult.builder()
                .success(false)
                .jobListing(firstJob)
                .status("FAILED")
                .message("平台拒绝投递")
                .build()).when(applicationClient).apply(any());

        String result = orchestrator.applyJobs("user-1", "投1号");

        assertTrue(result.contains("平台拒绝投递"));
    }

    @Test
    void requestWithoutSelectionShouldNotSearchOrApply() {
        String result = orchestrator.applyJobs("user-1", "帮我投简历");

        assertTrue(result.contains("请明确回复"));
        verify(jobSearchClient, never()).searchJobs(anyString(), anyString());
        verify(applicationClient, never()).apply(any());
    }

    private JobListing job(String jobId, String title, String company) {
        return JobListing.builder()
                .jobId(jobId)
                .jobKind("2")
                .platform("liepin")
                .title(title)
                .company(company)
                .salary("8-12K")
                .city("杭州")
                .build();
    }

    private ResumeOrchestrator newOrchestrator() {
        return new ResumeOrchestrator(
                jobSearchClient,
                applicationClient,
                applicationTracker,
                applicationSessionRepository
        );
    }

}
