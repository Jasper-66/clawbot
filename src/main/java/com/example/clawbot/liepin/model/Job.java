package com.example.clawbot.liepin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 猎聘职位模型 — 对应猎聘 API 返回的职位信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    /** 职位 ID */
    private String jobId;

    /** 职位名称 */
    private String title;

    /** 公司名称 */
    private String company;

    /** 工作城市 */
    private String city;

    /** 薪资范围，如 "20K-30K" */
    private String salary;

    /** 工作经验要求，如 "3-5年" */
    private String experience;

    /** 学历要求，如 "本科" */
    private String education;

    /** 职位描述 */
    private String description;

    /** 职位详情链接 */
    private String url;
}
