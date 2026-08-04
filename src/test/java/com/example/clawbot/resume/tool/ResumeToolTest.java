package com.example.clawbot.resume.tool;

import com.example.clawbot.resume.service.ResumeOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResumeToolTest {

    @Test
    void userIdShouldComeFromInternalToolContext() {
        ResumeOrchestrator orchestrator = mock(ResumeOrchestrator.class);
        when(orchestrator.searchJobs("wechat-user", "Java开发", "杭州"))
                .thenReturn("找到岗位");
        ResumeTool tool = new ResumeTool(orchestrator, new ObjectMapper());

        String result = tool.searchJobs(
                "Java开发", "杭州", new ToolContext(Map.of("userId", "wechat-user")));

        assertEquals("找到岗位", result);
        verify(orchestrator).searchJobs("wechat-user", "Java开发", "杭州");
    }
}
