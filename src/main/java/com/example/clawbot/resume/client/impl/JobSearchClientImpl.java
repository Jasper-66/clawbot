package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.JobSearchClient;
import com.example.clawbot.resume.model.JobListing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

// ── 成员2: 岗位搜索实现（空骨架）──
@Slf4j
@Service
public class JobSearchClientImpl implements JobSearchClient {

    @Override
    public List<JobListing> searchJobs(String keyword, String city, String experience, String salaryRange) {
        // TODO 成员2: 按 JobSearchClient.searchJobs() 注释实现
        throw new UnsupportedOperationException("TODO: 成员2实现 — 搜索匹配岗位");
    }

    @Override
    public JobListing getJobDetail(String jobId) {
        // TODO 成员2: 按 JobSearchClient.getJobDetail() 注释实现
        throw new UnsupportedOperationException("TODO: 成员2实现 — 获取岗位详情");
    }

    @Override
    public boolean hasApplied(String jobId, String userId) {
        // TODO 成员2: 按 JobSearchClient.hasApplied() 注释实现
        throw new UnsupportedOperationException("TODO: 成员2实现 — 检查是否已投递");
    }
}
