package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.ApplicationRecord;
import com.example.clawbot.resume.model.ApplicationResult;

import java.util.List;
import java.util.Map;

// ── 成员6: 投递记录与统计 ──
// 职责：持久化投递记录，提供查询和统计功能
public interface

ApplicationTracker {

    /**
     * 记录一次
     * 【被谁调用】ResumeOrchestrator.autoApply() 的第六步，投递成功后调用
     * 【返回值】  保存后的 ApplicationRecord
     *
     * 【实现流程】
     *   1. 从 ApplicationResult 提取信息，构建 ApplicationRecord
     *   2. 生成唯一 recordId
     *   3. 保存到数据库
     *   4. 返回保存后的记录
     */
    ApplicationRecord record(ApplicationResult result, String userId);

    /**
     * 批量记录
     * 【被谁调用】ResumeOrchestrator.batchApply() 完成后调用
     * 【返回值】  保存成功的记录数
     */
    int recordBatch(List<ApplicationResult> results, String userId);

    /**
     * 获取用户的投递记录列表。
     *
     * 【被谁调用】用户查询"我投了哪些公司"时调用
     * 【返回值】  List<ApplicationRecord>，按投递时间降序
     *
     * 【实现流程】
     *   1. 从数据库查询该用户的所有投递记录
     *   2. 按 appliedAt 降序排列
     *   3. 返回列表
     */
    List<ApplicationRecord> getRecords(String userId);

    /**
     * 更新投递状态。
     *
     * 【被谁调用】定时同步投递状态时，或用户手动更新时调用
     * 【返回值】  更新后的记录
     */
    ApplicationRecord updateStatus(String recordId, String newStatus);

    /**
     * 获取用户的投递统计数据。
     *
     * 【被谁调用】用户查询"我的求职进度"时调用
     * 【返回值】  Map 包含统计信息：total(总投递), viewed(被查看), interview(面试), rejected(被拒), offer(录用)
     *
     * 【实现流程】
     *   1. 查询该用户所有投递记录
     *   2. 按 status 分组计数
     *   3. 返回统计 Map
     */
    Map<String, Integer> getStatistics(String userId);
}
