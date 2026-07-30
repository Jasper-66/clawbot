package com.example.clawbot.resume.client;

import com.example.clawbot.resume.model.JobListing;

import java.util.List;

// ── 成员2: 岗位搜索与抓取 ──
// 职责：对接外部招聘平台API，实现岗位搜索、条件筛选和详情查询
public interface JobSearchClient {

    /**
     * 搜索匹配的岗位列表。
     *
     * 【被谁调用】ResumeOrchestrator.autoApply() 的第二步
     * 【返回值】  List<JobListing>，按发布时间降序；无结果返回空列表
     *
     * 【实现流程】
     *   1. 构建 HTTP GET 请求：平台 API 的 /jobs 端点，参数 keyword + city + experience + salary
     *   2. 使用 RestTemplate 发送请求，设置 API Key 到 Header
     *   3. 解析 JSON 响应 → 提取岗位ID、标题、公司、薪资、JD等字段
     *   4. 映射为 List<JobListing> 并返回
     *   5. 平台返回空或超时 → 返回空列表，不抛异常（让上层决定降级策略）
     */
    List<JobListing> searchJobs(String keyword, String city, String experience, String salaryRange);

    /**
     * 获取单个岗位详情。
     *
     * 【被谁调用】ResumeOrchestrator 中展示岗位详情给用户确认时调用
     * 【返回值】  JobListing 详情（含完整JD），不存在返回 null
     *
     * 【实现流程】
     *   1. GET /jobs/{jobId}，附 API Key
     *   2. 解析响应 → JobListing 对象（填充完整的 description 和 requiredSkills）
     *   3. 若 404 → 返回 null
     */
    JobListing getJobDetail(String jobId);

    /**
     * 检查用户是否已投递过该岗位。
     *
     * 【被谁调用】ResumeOrchestrator.autoApply() 中筛选岗位时调用，避免重复投递
     * 【返回值】  true=已投递
     *
     * 【实现流程】
     *   1. GET /jobs/{jobId}/applied?userId=xxx
     *   2. 平台返回投递状态
     *   3. 若查询失败 → 返回 false（不阻止投递流程）
     */
    boolean hasApplied(String jobId, String userId);
}
