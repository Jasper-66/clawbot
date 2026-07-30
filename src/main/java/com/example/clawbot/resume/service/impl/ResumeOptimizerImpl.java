package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.service.ResumeOptimizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// ── 成员4: 简历智能优化实现（空骨架）──
@Slf4j
@Service
public class
ResumeOptimizerImpl implements ResumeOptimizer {

    @Override
    public String generateOptimizationTip(UserProfile profile, JobListing job) {
        // TODO 成员4: 按 ResumeOptimizer.generateOptimizationTip() 注释实现
        throw new UnsupportedOperationException("TODO: 成员4实现 — 生成简历优化建议");
    }

    @Override
    public String generateCustomSummary(UserProfile profile, JobListing job) {
        // TODO 成员4: 按 ResumeOptimizer.generateCustomSummary() 注释实现
        throw new UnsupportedOperationException("TODO: 成员4实现 — 生成定制化简历摘要");
    }
}
