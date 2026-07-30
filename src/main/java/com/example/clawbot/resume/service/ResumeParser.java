package com.example.clawbot.resume.service;

import com.example.clawbot.resume.model.UserProfile;

// ── 成员1: 简历解析与存储 ──
// 职责：接收用户简历文件或自然语言描述，调用LLM将内容转为结构化简历数据
public interface ResumeParser {

    /**
     * 从自然语言消息中解析用户求职意向。
     *
     * 【被谁调用】ResumeOrchestrator.autoApply() 的第一步（当用户通过对话描述求职需求时）
     * 【返回值】  UserProfile（包含期望职位、城市、薪资、技能等）
     *
     * 【实现流程】
     *   1. 构建提示词：要求 LLM 从用户消息中提取 {期望职位, 城市, 薪资, 经验, 技能, 学历}
     *   2. 调用 LlmService.chat() 或直接调 DeepSeek API，temperature=0.1 保证稳定提取
     *   3. 解析 LLM 返回的 JSON，反序列化为 UserProfile 对象
     *   4. 缺失字段填默认值：城市=配置文件默认城市, 经验=不限
     *   5. 返回 UserProfile
     */
    UserProfile parseFromMessage(String userId, String userMessage);

    /**
     * 从简历文件（PDF/Word）中提取并解析简历内容。
     *
     * 【被谁调用】用户发送简历文件时，由 ResumeOrchestrator 调用
     * 【返回值】  UserProfile（包含完整的简历信息）
     *
     * 【实现流程】
     *   1. 使用 Apache PDFBox / POI 提取简历文本（复用 FileSummaryService 的能力）
     *   2. 构建提示词：要求 LLM 从简历文本中提取所有字段
     *   3. 解析 LLM 返回的 JSON → UserProfile
     *   4. 将原始简历文本保存到 UserProfile.rawResumeText
     *   5. 返回 UserProfile
     */
    UserProfile parseFromFile(String userId, byte[] fileBytes, String fileName);

    /**
     * 保存/更新用户简历到数据库。
     *
     * 【被谁调用】简历解析完成后，由 ResumeOrchestrator 调用
     * 【返回值】  保存后的 UserProfile（含生成的ID）
     *
     * 【实现流程】
     *   1. 检查数据库中是否已有该用户的简历
     *   2. 有 → 更新；无 → 新增
     *   3. 返回保存后的对象
     */
    UserProfile saveProfile(UserProfile profile);

    /**
     * 获取用户已保存的简历。
     *
     * 【被谁调用】ResumeOrchestrator 中需要获取用户简历时调用
     * 【返回值】  UserProfile，未找到返回 null
     */
    UserProfile getProfile(String userId);
}
