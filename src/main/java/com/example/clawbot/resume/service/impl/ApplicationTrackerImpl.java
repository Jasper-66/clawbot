package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.ApplicationRecord;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.service.ApplicationTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// ── 成员6: 投递记录与统计实现（空骨架）──
@Slf4j
@Service
public class ApplicationTrackerImpl implements ApplicationTracker {

    @Override
    public ApplicationRecord record(ApplicationResult result, String userId) {
        // TODO 成员6: 按 ApplicationTracker.record() 注释实现
        throw new UnsupportedOperationException("TODO: 成员6实现 — 记录单次投递");
    }

    @Override
    public int recordBatch(List<ApplicationResult> results, String userId) {
        // TODO 成员6: 按 ApplicationTracker.recordBatch() 注释实现
        throw new UnsupportedOperationException("TODO: 成员6实现 — 批量记录投递");
    }

    @Override
    public List<ApplicationRecord> getRecords(String userId) {
        // TODO 成员6: 按 ApplicationTracker.getRecords() 注释实现
        throw new UnsupportedOperationException("TODO: 成员6实现 — 查询投递记录");
    }

    @Override
    public ApplicationRecord updateStatus(String recordId, String newStatus) {
        // TODO 成员6: 按 ApplicationTracker.updateStatus() 注释实现
        throw new UnsupportedOperationException("TODO: 成员6实现 — 更新投递状态");
    }

    @Override
    public Map<String, Integer> getStatistics(String userId) {
        // TODO 成员6: 按 ApplicationTracker.getStatistics() 注释实现
        throw new UnsupportedOperationException("TODO: 成员6实现 — 获取投递统计");
    }
}
