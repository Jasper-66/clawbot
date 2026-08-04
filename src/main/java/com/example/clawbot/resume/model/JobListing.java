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
    /** 猎聘投递需要的岗位类型编号 */
    private String jobKind;
    /** 岗位来源平台 */
    private String platform;
    /** 岗位名称，如 "高级Java开发工程师" */
    private String title;
    /** 公司名称 */
    private String company;
    /** 工作城市 */
    private String city;
    /** 薪资范围，如 "20k-35k" */
    private String salary;
    /** 工作经验要求，如 "3-5年" */
    private String experienceRequired;
}
