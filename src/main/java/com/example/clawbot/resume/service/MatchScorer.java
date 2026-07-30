package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;

import java.util.List;
import java.util.Map;

// ── 成员3: 人岗匹配评分 ──
// 职责：纯算法模块，不调外部API。用LLM或规则引擎评估简历与岗位的匹配度
public interface MatchScorer {

    /**
     * 对单个岗位进行匹配评分。
     *
     * 【被谁调用】ResumeOrchestrator.autoApply() 的第三步
     * 【返回值】  0~100 的匹配分数
     *
     * 【实现流程】
     *   1. 构建提示词：将 UserProfile 和 JobListing 的 JD 一起传给 LLM
     *      要求 LLM 从以下维度评分（每项0~20分）：
     *      - 技能匹配度（简历技能 vs 岗位要求技能）
     *      - 经验匹配度（工作年限 vs 岗位要求）
     *      - 学历匹配度
     *      - 薪资匹配度（期望薪资 vs 岗位薪资）
     *      - 行业/岗位相关度
     *   2. 调用 LLM，temperature=0.1，要求返回 JSON: {"total": 85, "breakdown": {...}, "reason": "..."}
     *   3. 解析返回的总分
     *   4. 返回分数
     */
    int score(UserProfile profile, JobListing job);

    /**
     * 批量评分并排序。
     *
     * 【被谁调用】ResumeOrchestrator.autoApply() 中对搜索到的多个岗位评分
     * 【返回值】  Map<JobListing, Integer>，按分数降序排列
     *
     * 【实现流程】
     *   1. 遍历 jobs 列表，逐个调用 score()
     *   2. 按分数降序排列
     *   3. 过滤掉分数低于 minScore 的岗位
     *   4. 返回排序后的 Map
     */
    Map<JobListing, Integer> scoreAndRank(UserProfile profile, List<JobListing> jobs, int minScore);
}
