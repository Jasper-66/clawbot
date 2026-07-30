package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.*;

import java.util.List;
import java.util.Map;

// ── 成员7: 全流程编排（指挥家）──
// 职责：串联成员1~6的所有模块，处理用户交互流程（确认/重选/取消），提供统一入口
// 这是整个简历投递模块的"大脑"，其他6个成员都是被它调用的"手脚"
public interface ResumeOrchestrator {

    // ═══════════════════════════════════════════════════
    // 方法1: 搜索岗位 — 用户问"有什么合适的工作"
    // ═══════════════════════════════════════════════════
    /**
     * 搜索匹配用户简历的岗位列表。
     *
     * 【被谁调用】ResumeTool.searchJobs() → LLM Function Calling
     * 【返回值】  格式化的岗位列表文本，可直接发给用户
     *
     * 【实现流程】
     *   1. 从 ResumeParser 获取用户简历/求职意向（成员1）
     *   2. 调用 Member2 JobSearchClient.searchJobs(keyword, city, experience, salary)
     *   3. 若结果为空 → 返回"当前条件未找到匹配岗位，请调整搜索条件"
     *   4. 取前5个岗位，格式化为微信可读文本：
     *      "📋 为您找到以下岗位：\n1. 高级Java开发 @ 字节跳动 25k-40k 北京\n2. ..."
     *   5. 返回格式化文本 → LLM → 发送给用户
     */
    String searchJobs(String userId, String keyword, String city);

    // ═══════════════════════════════════════════════════
    // 方法2: 一键自动投递（核心方法）
    // ═══════════════════════════════════════════════════
    /**
     * 全自动投递：解析简历 → 搜岗位 → 匹配评分 → 优化简历 → 投递 → 记录。
     *
     * 【被谁调用】ResumeTool.autoApply() → LLM Function Calling
     * 【返回值】  投递结果汇总文本；失败时抛异常由Tool层捕获后返回错误文本
     *
     * 【实现流程（串联6个成员）】
     *   Step1 — 调 Member1 ResumeParser.parseFromMessage(userId, message) → UserProfile
     *           若用户已有简历 → 调 ResumeParser.getProfile(userId) 直接使用
     *   Step2 — 调 Member2 JobSearchClient.searchJobs(desiredPosition, city, experience, salary)
     *           → List<JobListing>
     *           若无结果 → 抛异常"未找到匹配岗位，建议调整搜索条件"
     *   Step3 — 调 Member3 MatchScorer.scoreAndRank(profile, jobs, 60) → Map<JobListing, Integer>
     *           取评分 >= 60 的岗位，按分数降序取前3个
     *           若无满足条件 → 抛异常"未找到足够匹配的岗位"
     *   Step4 — 对每个目标岗位，调 Member4 ResumeOptimizer.generateOptimizationTip(profile, job)
     *           生成优化建议展示给用户确认
     *   Step5 — 用户确认后，调 Member5 ApplicationClient.apply(job, profile) → ApplicationResult
     *           若用户取消 → 返回"已取消投递"
     *   Step6 — 调 Member6 ApplicationTracker.record(result, userId) → 记录投递
     *   Step7 — 格式化返回："✅ 投递成功！\n📋 高级Java开发 @ 字节跳动\n📊 匹配度: 85分\n..."
     */
    ApplicationResult autoApply(String userId, String userMessage);

    // ═══════════════════════════════════════════════════
    // 方法3: 批量投递 — 用户说"帮我多投几个"
    // ═══════════════════════════════════════════════════
    /**
     * 批量自动投递多个岗位。
     *
     * 【被谁调用】ResumeTool.batchApply() → LLM Function Calling
     * 【返回值】  批量投递结果汇总
     *
     * 【实现流程】
     *   1. 调 Step1~3 同 autoApply，获取 Top N 匹配岗位
     *   2. 调 Member5 ApplicationClient.batchApply(topJobs, profile)
     *   3. 调 Member6 ApplicationTracker.recordBatch(results, userId)
     *   4. 格式化汇总："📊 批量投递完成：成功 5 个，失败 1 个\n..."
     */
    String batchApply(String userId, String userMessage, int maxCount);

    // ═══════════════════════════════════════════════════
    // 方法4: 查询投递进度 — 用户问"我投了多少家了"
    // ═══════════════════════════════════════════════════
    /**
     * 查询用户的投递记录和统计数据。
     *
     * 【被谁调用】ResumeTool.getApplicationStatus() → LLM Function Calling
     * 【返回值】  格式化的投递统计和最近记录
     *
     * 【实现流程】
     *   1. 调 Member6 ApplicationTracker.getStatistics(userId) → Map
     *   2. 调 Member6 ApplicationTracker.getRecords(userId) → List<ApplicationRecord>
     *   3. 格式化输出：
     *      "📊 求职进度：\n总投递: 15 | 已查看: 8 | 面试: 3 | 待定: 4\n\n最近投递：\n1. 字节跳动 - 7月28日..."
     */
    String getApplicationProgress(String userId);

    // ═══════════════════════════════════════════════════
    // 内部辅助方法
    // ═══════════════════════════════════════════════════

    /**
     * 向用户发送确认消息（微信交互式确认）。
     *
     * 【被谁调用】autoApply() 中 Step4 完成后、投递前
     * 【返回值】  true=用户确认 / false=用户取消
     *
     * 【实现流程】
     *   1. 构建确认文本："即将为您投递：\n📋 高级Java开发 @ 字节跳投\n📊 匹配度: 85分..."
     *   2. 通过 WeChatBotService 发送给用户，等待回复"确认"或"取消"
     *   3. 需实现等待用户回复的机制（可复用现有的消息处理循环）
     */
    boolean requestUserConfirmation(String userId, JobListing job, int matchScore);
}
