package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;

import java.util.List;
import java.util.Map;

/** 计算简历与岗位的匹配程度。 */
public interface MatchScorer {

    int score(UserProfile profile, JobListing job);

    Map<JobListing, Integer> scoreAndRank(UserProfile profile, List<JobListing> jobs);
}
