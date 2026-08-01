package com.example.clawbot.resume.client;

import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;

/** 向招聘平台提交一条职位申请。 */
public interface ApplicationClient {

    ApplicationResult apply(JobListing job, UserProfile userProfile);
}
