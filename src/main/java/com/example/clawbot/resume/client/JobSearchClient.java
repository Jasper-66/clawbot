package com.example.clawbot.resume.client;

import com.example.clawbot.resume.model.JobListing;

import java.util.List;

/** 搜索招聘平台岗位并检查重复投递。 */
public interface JobSearchClient {

    List<JobListing> searchJobs(String keyword, String city, String experience, String salaryRange);

    boolean hasApplied(String jobId, String userId);
}
