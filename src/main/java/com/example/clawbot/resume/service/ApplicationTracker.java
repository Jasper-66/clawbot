package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.ApplicationRecord;
import com.example.clawbot.resume.model.ApplicationResult;

import java.util.List;
import java.util.Map;

/** 保存和查询投递记录。 */
public interface ApplicationTracker {

    ApplicationRecord record(ApplicationResult result, String userId);

    List<ApplicationRecord> getRecords(String userId);

    ApplicationRecord updateStatus(String recordId, String newStatus);

    Map<String, Integer> getStatistics(String userId);
}
