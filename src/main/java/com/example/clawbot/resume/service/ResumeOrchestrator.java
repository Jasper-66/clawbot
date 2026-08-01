package com.example.clawbot.resume.service;

/** 编排岗位搜索、投递确认和进度查询。 */
public interface ResumeOrchestrator {

    String searchJobs(String userId, String keyword, String city);

    String prepareApplication(String userId, String userMessage);

    String confirmApplication(String userId);

    String getApplicationProgress(String userId);
}
