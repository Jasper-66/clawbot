package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.UserProfile;

/** 解析并保存用户简历。 */
public interface ResumeParser {

    UserProfile parseFromMessage(String userId, String userMessage);

    UserProfile parseFromFile(String userId, byte[] fileBytes, String fileName);

    UserProfile parseFromImage(String userId, byte[] imageBytes, String fileName);

    UserProfile saveProfile(UserProfile profile);

    UserProfile getProfile(String userId);
}
