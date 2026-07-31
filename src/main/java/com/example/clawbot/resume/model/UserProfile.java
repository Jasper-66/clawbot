package com.example.clawbot.resume.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// 用户简历/求职意向（从简历文件或对话中解析出的结构化信息）
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {
    /** 用户ID（微信用户） */
    private String userId;
    /** 姓名 */
    private String name;
    /** 手机号 */
    private String phone;
    /** 邮箱 */
    private String email;
    /** 求职意向：期望职位关键词，如 "Java后端开发" */
    private String desiredPosition;
    /** 期望城市 */
    private String desiredCity;
    /** 期望薪资范围，如 "15k-25k" */
    private String salaryRange;
    /** 工作经验年数 */
    private Integer experienceYears;
    /** 最高学历：本科/硕士/博士等 */
    private String education;
    /** 学校名称，如 "北京大学" */
    private String school;
    /** 学校层次：985/211/双一流/一本/二本/海外名校 等 */
    private String schoolTier;
    /** 技能标签列表，如 ["Java", "Spring", "MySQL"] */
    private List<String> skills;
    /** 自我介绍/个人总结 */
    private String summary;
    /** 工作经历列表（原简历文本） */
    private List<String> workHistory;
    /** 项目经历列表（原简历文本） */
    private List<String> projectHistory;
    /** 原始简历文本（完整） */
    private String rawResumeText;
    /** 简历文件路径（PDF/Word） */
    private String resumeFilePath;
}
