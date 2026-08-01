package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.client.LiepinMcpClientService;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "liepin")
public class LiepinApplicationClient implements ApplicationClient {

    private static final String[] MESSAGE_FIELDS = {
            "result", "message", "msg", "errorMessage", "errorMsg", "errmsg", "reason", "detail", "error"
    };
    private static final String[] APPLICATION_ID_FIELDS = {"applicationId", "applyId"};

    private final LiepinMcpClientService mcpClient;

    @Override
    public ApplicationResult apply(JobListing job, UserProfile userProfile) {
        if (job == null
                || job.getJobId() == null
                || job.getJobId().isBlank()
                || job.getJobKind() == null
                || job.getJobKind().isBlank()) {
            throw new IllegalArgumentException("猎聘投递需要有效的 jobId 和 jobKind");
        }

        try {
            JsonNode response = mcpClient.applyJob(job.getJobId(), job.getJobKind());
            boolean success = isSuccess(response);
            String message = firstText(response, MESSAGE_FIELDS);

            log.info("[猎聘 MCP] 投递返回: jobId={}, jobKind={}, success={}, response={}",
                    job.getJobId(), job.getJobKind(), success, response);
            return ApplicationResult.builder()
                    .success(success)
                    .jobListing(job)
                    .applicationId(firstText(response, APPLICATION_ID_FIELDS))
                    .status(success ? "SUBMITTED" : "FAILED")
                    .message(message.isBlank() ? (success ? "猎聘投递成功" : "猎聘投递失败") : message)
                    .appliedAt(OffsetDateTime.now().toString())
                    .build();
        } catch (Exception exception) {
            log.error("[猎聘 MCP] 投递失败: jobId={}, jobKind={}, error={}",
                    job.getJobId(), job.getJobKind(), exception.getMessage(), exception);
            return ApplicationResult.builder()
                    .success(false)
                    .jobListing(job)
                    .status("FAILED")
                    .message(exception.getMessage())
                    .appliedAt(OffsetDateTime.now().toString())
                    .build();
        }
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.findValue(fieldName);
            if (value != null && value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private boolean isSuccess(JsonNode response) {
        JsonNode data = response.path("data");
        if (data.isBoolean()) {
            return data.asBoolean();
        }

        JsonNode success = firstNode(response, "success", "ok");
        if (success != null && success.isBoolean()) {
            return success.asBoolean();
        }

        String message = firstText(response, MESSAGE_FIELDS);
        if (isFailureMessage(message)) {
            return false;
        }
        if (isApplicationSuccess(message)) {
            return true;
        }

        JsonNode errorCode = firstNode(response, "errCode");
        if (errorCode != null && errorCode.asInt(0) != 0) {
            return false;
        }

        JsonNode code = firstNode(response, "code", "statusCode");
        if (code != null && code.isValueNode()) {
            String value = code.asText();
            if ("0".equals(value) || "200".equals(value)) {
                return true;
            }
        }

        String status = firstText(response, "status", "state");
        if ("success".equalsIgnoreCase(status) || "submitted".equalsIgnoreCase(status)) {
            return true;
        }

        if (!firstText(response, APPLICATION_ID_FIELDS).isBlank()) {
            return true;
        }
        return false;
    }

    private boolean isFailureMessage(String message) {
        return message.contains("失败") || message.contains("不成功")
                || message.contains("不可投递") || message.contains("拒绝")
                || message.contains("不足") || message.contains("异常")
                || message.contains("错误");
    }

    private boolean isApplicationSuccess(String message) {
        boolean applicationMessage = message.contains("投递")
                || message.contains("申请")
                || message.contains("应聘");
        boolean completed = message.contains("成功")
                || message.contains("已投递") || message.contains("已经投递")
                || message.contains("已申请") || message.contains("已经申请")
                || message.contains("已应聘") || message.contains("已经应聘");
        return applicationMessage && completed;
    }

    private JsonNode firstNode(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.findValue(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }
}
