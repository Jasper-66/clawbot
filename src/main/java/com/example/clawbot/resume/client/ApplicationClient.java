package com.example.clawbot.resume.client;

import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;

// ── 成员5: 投递执行 ──
// 职责：对接招聘平台API，执行简历投递、查询投递状态
public interface ApplicationClient {

    /**
     * 向指定岗位投递简历。
     *
     * 【被谁调用】ResumeOrchestrator.autoApply() 的第五步
     * 【返回值】  ApplicationResult，包含投递是否成功、平台返回的状态
     *
     * 【实现流程】
     *   1. POST /applications  body: {jobId, userId, resumeData}
     *   2. 平台验证用户简历完整性（如缺少手机号会报错）
     *   3. 平台返回 applicationId + 状态
     *   4. 构建 ApplicationResult 对象
     *   5. 若平台返回错误（如岗位已下架、已投递）→ 返回 success=false + 错误信息
     */
    ApplicationResult apply(JobListing job, UserProfile userProfile);

    /**
     * 批量投递（一键投递多个岗位）。
     *
     * 【被谁调用】ResumeOrchestrator.batchApply() 中调用
     * 【返回值】  List<ApplicationResult>，每个岗位一个结果
     *
     * 【实现流程】
     *   1. 遍历 jobs 列表，逐个调用 apply()
     *   2. 每次投递间隔 2~5 秒随机延迟（避免被平台反爬封禁）
     *   3. 单个投递失败不影响其他投递，记录失败原因
     *   4. 返回所有投递结果
     */
    java.util.List<ApplicationResult> batchApply(java.util.List<JobListing> jobs, UserProfile userProfile);

    /**
     * 查询投递状态。
     *
     * 【被谁调用】用户询问"我的简历被查看了吗"时，由 Member7 调用
     * 【返回值】  ApplicationResult 当前状态
     *
     * 【实现流程】
     *   1. GET /applications/{applicationId}
     *   2. 返回最新状态（SUBMITTED / VIEWED / INTERVIEW / REJECTED）
     */
    ApplicationResult getApplicationStatus(String applicationId);
}
