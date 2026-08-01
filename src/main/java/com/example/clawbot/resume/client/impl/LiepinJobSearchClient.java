package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.client.LiepinMcpClientService;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.repository.ApplicationRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "resume.platform.provider", havingValue = "liepin")
public class LiepinJobSearchClient implements JobSearchClient {

    private final LiepinMcpClientService mcpClient;
    private final ApplicationRecordRepository applicationRecordRepository;

    @Override
    public List<JobListing> searchJobs(
            String keyword,
            String city,
            String experience,
            String salaryRange
    ) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jobName", keyword.trim());
        putIfPresent(request, "address", city);
        if (experience != null && !experience.contains("不限")) {
            putIfPresent(request, "workExperience", experience);
        }
        request.put("page", 0);

        JsonNode items = findJobItems(mcpClient.searchJobs(request));
        if (!items.isArray()) {
            return Collections.emptyList();
        }

        List<JobListing> jobs = new ArrayList<>();
        for (JsonNode item : items) {
            JobListing job = mapJob(item);
            if (job.getJobId().isBlank() || job.getJobKind().isBlank()) {
                List<String> fields = new ArrayList<>();
                item.fieldNames().forEachRemaining(fields::add);
                log.warn("[猎聘 MCP] 忽略缺少 jobId/jobKind 的岗位: title={}, fields={}",
                        job.getTitle(), fields);
                continue;
            }
            jobs.add(job);
        }

        log.info("[猎聘 MCP] 搜索完成: keyword={}, city={}, count={}",
                keyword, city, jobs.size());
        return jobs;
    }

    @Override
    public boolean hasApplied(String jobId, String userId) {
        return applicationRecordRepository.existsByUserIdAndJobId(userId, jobId);
    }

    private JobListing mapJob(JsonNode item) {
        String location = firstText(item, "location", "address", "city");
        List<String> tags = new ArrayList<>();
        item.path("companyTags").forEach(tag -> tags.add(tag.asText()));

        return JobListing.builder()
                .jobId(firstText(item, "jobId", "id"))
                .jobKind(firstText(item, "jobKind", "jobType", "kind"))
                .platform("liepin")
                .title(firstText(item, "jobName", "title"))
                .company(firstText(item, "company", "companyName"))
                .companySize(firstText(item, "companySize"))
                .industry(firstText(item, "industry"))
                .city(location)
                .district(location)
                .salary(firstText(item, "salary", "salaryDesc"))
                .experienceRequired(firstText(item, "workYears", "experience"))
                .educationRequired(firstText(item, "education", "degree"))
                .requiredSkills(tags)
                .detailUrl(firstText(item, "jobDetailUrl", "detailUrl", "url"))
                .build();
    }

    private JsonNode findJobItems(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        JsonNode data = root.path("data");
        if (data.isArray()) {
            return data;
        }
        if (data.path("list").isArray()) {
            return data.path("list");
        }
        if (data.path("jobs").isArray()) {
            return data.path("jobs");
        }
        if (root.path("list").isArray()) {
            return root.path("list");
        }
        if (root.path("jobs").isArray()) {
            return root.path("jobs");
        }
        JsonNode result = root.path("result");
        if (result.isArray()) {
            return result;
        }
        if (result.path("list").isArray()) {
            return result.path("list");
        }
        return result.path("jobs");
    }

    private void putIfPresent(Map<String, Object> request, String key, String value) {
        if (value != null && !value.isBlank()) {
            request.put(key, value.trim());
        }
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }
}
