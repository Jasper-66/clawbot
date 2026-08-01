package com.example.clawbot.resume.service;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.repository.ApplicationSessionRepository;
import com.example.clawbot.resume.service.impl.ResumeOrchestratorImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResumeOrchestratorSelectionTest {

    @TempDir
    Path tempDir;

    private final ResumeParser resumeParser = mock(ResumeParser.class);
    private final JobSearchClient jobSearchClient = mock(JobSearchClient.class);
    private final MatchScorer matchScorer = mock(MatchScorer.class);
    private final ApplicationClient applicationClient = mock(ApplicationClient.class);
    private final ApplicationTracker applicationTracker = mock(ApplicationTracker.class);

    private ResumeOrchestratorImpl orchestrator;
    private ApplicationSessionRepository applicationSessionRepository;
    private SingleConnectionDataSource dataSource;
    private UserProfile profile;
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
        profile = UserProfile.builder()
                .userId("user-1")
                .name("测试用户")
                .desiredPosition("外卖")
                .desiredCity("杭州")
                .salaryRange("不限")
                .experienceYears(2)
                .skills(List.of())
                .workHistory(List.of())
                .projectHistory(List.of())
                .build();
        firstJob = job("1001", "外卖站长", "甲公司");
        secondJob = job("1002", "配送员", "乙公司");

        when(resumeParser.getProfile("user-1")).thenReturn(profile);
        when(jobSearchClient.searchJobs("外卖", "杭州", "2年", "不限"))
                .thenReturn(List.of(firstJob, secondJob));
        when(jobSearchClient.hasApplied(anyString(), anyString())).thenReturn(false);
        when(matchScorer.score(any(), any())).thenReturn(80);
        when(applicationClient.apply(any(), any())).thenAnswer(invocation -> {
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
    void allSelectionShouldReuseRecentSearchAndApplyAfterConfirmation() {
        orchestrator.searchJobs("user-1", "外卖", "杭州");

        orchestrator = newOrchestrator();

        String prepared = orchestrator.prepareApplication("user-1", "全部投递");

        assertTrue(prepared.contains("外卖站长"));
        assertTrue(prepared.contains("配送员"));
        verify(jobSearchClient, times(1))
                .searchJobs("外卖", "杭州", "2年", "不限");

        orchestrator = newOrchestrator();
        String result = orchestrator.confirmApplication("user-1");

        assertTrue(result.contains("成功 2 个"));
        verify(applicationClient, times(2)).apply(any(), any());
        verify(applicationTracker, times(2)).record(any(), anyString());
        assertTrue(applicationSessionRepository.findLatestPendingApplication("user-1").isEmpty());
    }

    @Test
    void numberedSelectionShouldPrepareOnlySelectedJob() {
        orchestrator.searchJobs("user-1", "外卖", "杭州");

        String prepared = orchestrator.prepareApplication("user-1", "投2号");

        assertFalse(prepared.contains("外卖站长"));
        assertTrue(prepared.contains("配送员"));
    }

    @Test
    void failedApplicationShouldKeepPendingTaskForRetry() {
        orchestrator.searchJobs("user-1", "外卖", "杭州");
        orchestrator.prepareApplication("user-1", "投1号");
        doReturn(ApplicationResult.builder()
                .success(false)
                .jobListing(firstJob)
                .status("FAILED")
                .message("平台拒绝投递")
                .build()).when(applicationClient).apply(any(), any());

        String result = orchestrator.confirmApplication("user-1");

        assertTrue(result.contains("平台拒绝投递"));
        assertTrue(applicationSessionRepository.findLatestPendingApplication("user-1").isPresent());
    }

    @Test
    void lowScoreShouldStillCreatePendingApplication() {
        when(matchScorer.scoreAndRank(any(), any()))
                .thenReturn(Map.of(firstJob, 5));

        String prepared = orchestrator.prepareApplication("user-1", "帮我投简历");

        assertTrue(prepared.contains("外卖站长"));
        assertTrue(prepared.contains("5分"));
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
                .requiredSkills(List.of())
                .build();
    }

    private ResumeOrchestratorImpl newOrchestrator() {
        return new ResumeOrchestratorImpl(
                resumeParser,
                jobSearchClient,
                matchScorer,
                applicationClient,
                applicationTracker,
                applicationSessionRepository
        );
    }

}
