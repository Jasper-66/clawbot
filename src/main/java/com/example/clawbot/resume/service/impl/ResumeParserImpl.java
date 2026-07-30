package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.service.ResumeParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// ── 成员1: 简历解析实现（空骨架）──
@Slf4j
@Service
public class ResumeParserImpl implements ResumeParser {

    @Override
    public UserProfile parseFromMessage(String userId, String userMessage) {
        // TODO 成员1: 按 ResumeParser.parseFromMessage() 注释实现
        throw new UnsupportedOperationException("TODO: 成员1实现 — 从对话中解析求职意向");
    }

    @Override
    public UserProfile parseFromFile(String userId, byte[] fileBytes, String fileName) {
        // TODO 成员1: 按 ResumeParser.parseFromFile() 注释实现
        throw new UnsupportedOperationException("TODO: 成员1实现 — 从简历文件中解析内容");
    }

    @Override
    public UserProfile saveProfile(UserProfile profile) {
        // TODO 成员1: 按 ResumeParser.saveProfile() 注释实现
        throw new UnsupportedOperationException("TODO: 成员1实现 — 保存用户简历");
    }

    @Override
    public UserProfile getProfile(String userId) {
        // TODO 成员1: 按 ResumeParser.getProfile() 注释实现
        throw new UnsupportedOperationException("TODO: 成员1实现 — 获取用户简历");
    }
}
