package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.service.MatchScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// ── 成员3: 人岗匹配评分实现（空骨架）──
@Slf4j
@Service
public class MatchScorerImpl implements MatchScorer {

    @Override
    public int score(UserProfile profile, JobListing job) {
        // TODO 成员3: 按 MatchScorer.score() 注释实现
        throw new UnsupportedOperationException("TODO: 成员3实现 — 单岗位匹配评分");
    }

    @Override
    public Map<JobListing, Integer> scoreAndRank(UserProfile profile, List<JobListing> jobs, int minScore) {
        // TODO 成员3: 按 MatchScorer.scoreAndRank() 注释实现
        throw new UnsupportedOperationException("TODO: 成员3实现 — 批量评分排序");
    }
}
