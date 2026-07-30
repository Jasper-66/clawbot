package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;

// ── 成员4: 简历智能优化 ──
// 职责：针对目标岗位，用LLM优化简历内容，提高匹配度
public interface ResumeOptimizer {

    /**
     * 针对目标岗位生成简历优化建议。
     *
     * 【被谁调用】ResumeOrchestrator.autoApply() 的第四步
     * 【返回值】  优化建议文本（可直接展示给用户）
     *
     * 【实现流程】
     *   1. 构建提示词：将 UserProfile + JobListing 的 JD 传给 LLM
     *      要求 LLM 分析简历与岗位的差距，给出具体优化建议：
     *      - 哪些技能需要突出
     *      - 哪些项目经历需要重新描述
     *      - 建议添加的关键词
     *   2. 调用 LLM，生成优化建议
     *   3. 返回建议文本
     */
    String generateOptimizationTip(UserProfile profile, JobListing job);

    /**
     * 生成针对特定岗位的定制化简历摘要。
     *
     * 【被谁调用】投递时附带定制化自我介绍
     * 【返回值】  定制化的简历摘要/自我介绍文本
     *
     * 【实现流程】
     *   1. 提取岗位 JD 中的核心要求关键词
     *   2. 从用户简历中挑选最匹配的经历和技能
     *   3. 用 LLM 生成一段 150~200 字的定制化自我介绍
     *   4. 返回文本
     */
    String generateCustomSummary(UserProfile profile, JobListing job);
}
