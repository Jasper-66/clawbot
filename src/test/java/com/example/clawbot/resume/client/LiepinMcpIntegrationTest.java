package com.example.clawbot.resume.client;

import com.example.clawbot.resume.client.impl.LiepinApplicationClient;
import com.example.clawbot.resume.client.impl.LiepinJobSearchClient;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiepinMcpIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mcpClientServiceShouldDiscoverAndCallRemoteTool() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool = mock(McpSchema.Tool.class);
        when(tool.name()).thenReturn("user-search-job");
        when(client.listTools()).thenReturn(
                new McpSchema.ListToolsResult(List.of(tool), null)
        );
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
                new McpSchema.CallToolResult(
                        "{\"code\":0,\"data\":{\"list\":[]}}",
                        false
                )
        );
        LiepinMcpClientService service = new LiepinMcpClientService(
                List.of(client),
                objectMapper
        );

        assertEquals(0, service.searchJobs(Map.of("jobName", "Java开发"))
                .path("code").asInt());
    }

    @Test
    void searchClientShouldMapLiepinJobFields() throws Exception {
        LiepinMcpClientService mcpClient = mock(LiepinMcpClientService.class);
        ApplicationRecordRepository repository = mock(ApplicationRecordRepository.class);
        when(mcpClient.searchJobs(anyMap())).thenReturn(objectMapper.readTree("""
                {
                  "code": 0,
                  "data": {
                    "list": [{
                      "jobId": 84541619,
                      "jobKind": "2",
                      "jobName": "Java开发工程师",
                      "company": "示例公司",
                      "location": "北京",
                      "salary": "15-25k",
                      "education": "本科",
                      "workYears": "3-5年",
                      "industry": "互联网",
                      "companySize": "100-499人",
                      "companyTags": ["五险一金"],
                      "jobDetailUrl": "https://www.liepin.com/job/1.shtml"
                    }]
                  }
                }
                """));
        LiepinJobSearchClient client = new LiepinJobSearchClient(mcpClient, repository);

        List<JobListing> jobs = client.searchJobs("Java开发", "北京", "3-5年", "不限");

        assertEquals(1, jobs.size());
        assertEquals("84541619", jobs.getFirst().getJobId());
        assertEquals("2", jobs.getFirst().getJobKind());
        assertEquals("liepin", jobs.getFirst().getPlatform());
    }

    @Test
    void applicationClientShouldUseMcpApplyResult() throws Exception {
        LiepinMcpClientService mcpClient = mock(LiepinMcpClientService.class);
        when(mcpClient.applyJob("84541619", "2")).thenReturn(objectMapper.readTree("""
                {"code":0,"data":{"applicationId":"LP-1001","message":"投递成功"}}
                """));
        LiepinApplicationClient client = new LiepinApplicationClient(mcpClient);
        JobListing job = JobListing.builder()
                .jobId("84541619")
                .jobKind("2")
                .platform("liepin")
                .title("Java开发工程师")
                .company("示例公司")
                .build();

        ApplicationResult result = client.apply(job, null);

        assertTrue(result.isSuccess());
        assertEquals("LP-1001", result.getApplicationId());
        assertEquals("SUBMITTED", result.getStatus());
    }

    @Test
    void applicationClientShouldKeepRemoteFailureMessage() throws Exception {
        LiepinMcpClientService mcpClient = mock(LiepinMcpClientService.class);
        when(mcpClient.applyJob("84541619", "2")).thenReturn(objectMapper.readTree("""
                {"code":40001,"message":"该职位暂不可投递"}
                """));
        LiepinApplicationClient client = new LiepinApplicationClient(mcpClient);
        JobListing job = JobListing.builder()
                .jobId("84541619")
                .jobKind("2")
                .platform("liepin")
                .build();

        ApplicationResult result = client.apply(job, null);

        assertFalse(result.isSuccess());
        assertEquals("FAILED", result.getStatus());
        assertEquals("该职位暂不可投递", result.getMessage());
    }

    @Test
    void applicationClientShouldReadNestedResultFields() throws Exception {
        LiepinMcpClientService mcpClient = mock(LiepinMcpClientService.class);
        when(mcpClient.applyJob("84541619", "2")).thenReturn(objectMapper.readTree("""
                {"result":{"ok":true,"applyId":"LP-2002","msg":"申请成功"}}
                """));
        LiepinApplicationClient client = new LiepinApplicationClient(mcpClient);
        JobListing job = JobListing.builder()
                .jobId("84541619")
                .jobKind("2")
                .platform("liepin")
                .build();

        ApplicationResult result = client.apply(job, null);

        assertTrue(result.isSuccess());
        assertEquals("LP-2002", result.getApplicationId());
        assertEquals("申请成功", result.getMessage());
    }

    @Test
    void applicationClientShouldReadAlternativeErrorMessage() throws Exception {
        LiepinMcpClientService mcpClient = mock(LiepinMcpClientService.class);
        when(mcpClient.applyJob("84541619", "2")).thenReturn(objectMapper.readTree("""
                {"result":{"success":false,"errorMessage":"简历信息不完整"}}
                """));
        LiepinApplicationClient client = new LiepinApplicationClient(mcpClient);
        JobListing job = JobListing.builder()
                .jobId("84541619")
                .jobKind("2")
                .platform("liepin")
                .build();

        ApplicationResult result = client.apply(job, null);

        assertFalse(result.isSuccess());
        assertEquals("简历信息不完整", result.getMessage());
    }

    @Test
    void applicationClientShouldRecognizeLiepinResultSuccess() throws Exception {
        LiepinMcpClientService mcpClient = mock(LiepinMcpClientService.class);
        when(mcpClient.applyJob("84280139", "2")).thenReturn(objectMapper.readTree("""
                {"data":{"result":"应聘成功"},"errCode":0}
                """));
        LiepinApplicationClient client = new LiepinApplicationClient(mcpClient);
        JobListing job = JobListing.builder()
                .jobId("84280139")
                .jobKind("2")
                .platform("liepin")
                .build();

        ApplicationResult result = client.apply(job, null);

        assertTrue(result.isSuccess());
        assertEquals("应聘成功", result.getMessage());
        assertEquals("SUBMITTED", result.getStatus());
    }

    @Test
    void applicationClientShouldKeepLiepinBusinessFailure() throws Exception {
        LiepinMcpClientService mcpClient = mock(LiepinMcpClientService.class);
        when(mcpClient.applyJob("84280139", "2")).thenReturn(objectMapper.readTree("""
                {"data":{"result":"您的简历完整度不足65%，请先完善简历"},"errCode":0}
                """));
        LiepinApplicationClient client = new LiepinApplicationClient(mcpClient);
        JobListing job = JobListing.builder()
                .jobId("84280139")
                .jobKind("2")
                .platform("liepin")
                .build();

        ApplicationResult result = client.apply(job, null);

        assertFalse(result.isSuccess());
        assertEquals("您的简历完整度不足65%，请先完善简历", result.getMessage());
        assertEquals("FAILED", result.getStatus());
    }

    @Test
    void mcpClientShouldRejectInvalidApplyIdentifiers() {
        LiepinMcpClientService service = new LiepinMcpClientService(
                List.of(),
                objectMapper
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.applyJob("84541619", " "));
        assertThrows(IllegalArgumentException.class,
                () -> service.applyJob("not-a-number", "2"));
    }
}
