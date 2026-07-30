package com.example.clawbot.resume.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 岗位信息（从招聘平台搜索到的职位）
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobListing {
    /** 岗位ID（平台侧） */
    private String jobId;
    /** 岗位名称，如 "高级Java开发工程师" */
    private String title;
    /** 公司名称 */
    private String company;
    /** 公司规模，如 "100-499人" */
    private String companySize;
    /** 公司行业，如 "互联网/电子商务" */
    private String industry;
    /** 工作城市 */
    private String city;
    /** 工作区域，如 "朝阳区" */
    private String district;
    /** 薪资范围，如 "20k-35k" */
    private String salary;
    /** 工作经验要求，如 "3-5年" */
    private String experienceRequired;
    /** 学历要求，如 "本科" */
    private String educationRequired;
    /** 岗位描述/JD全文 */
    private String description;
    /** 技能要求标签，如 ["Java", "Spring Boot", "微服务"] */
    private java.util.List<String> requiredSkills;
    /** 岗位详情URL */
    private String detailUrl;
    /** 发布日期，如 2026-07-28 */
    private String publishDate;
    /** 是否已投递 */
    private boolean applied;
}
