package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

// ── 成员5: 投递执行实现（空骨架）──
@Slf4j
@Service
public class ApplicationClientImpl implements ApplicationClient {

    @Override
    public ApplicationResult apply(JobListing job, UserProfile userProfile) {
        // TODO 成员5: 按 ApplicationClient.apply() 注释实现
        throw new UnsupportedOperationException("TODO: 成员5实现 — 执行单次投递");
    }

    @Override
    public List<ApplicationResult> batchApply(List<JobListing> jobs, UserProfile userProfile) {
        // TODO 成员5: 按 ApplicationClient.batchApply() 注释实现
        throw new UnsupportedOperationException("TODO: 成员5实现 — 批量投递");
    }

    @Override
    public ApplicationResult getApplicationStatus(String applicationId) {
        // TODO 成员5: 按 ApplicationClient.getApplicationStatus() 注释实现
        throw new UnsupportedOperationException("TODO: 成员5实现 — 查询投递状态");
    }
}
