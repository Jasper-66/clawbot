package com.example.clawbot.resume.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// SQLite 中的一次岗位投递记录。
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationRecord {
    /** 记录ID */
    private String recordId;
    /** 用户ID */
    private String userId;
    /** 招聘平台岗位ID，用于避免重复投递 */
    private String jobId;
    /** 平台返回的投递ID */
    private String applicationId;
    /** 招聘平台 */
    private String platform;
    /** 岗位名称 */
    private String jobTitle;
    /** 公司名称 */
    private String company;
    /** 薪资范围 */
    private String salary;
    /** 工作城市 */
    private String city;
    /** PROCESSING / SUBMITTED / FAILED / UNKNOWN */
    private String status;
    /** 投递时间 */
    private String appliedAt;
    /** 最后更新时间 */
    private String updatedAt;
    /** 备注（用户可添加） */
    private String remark;
}
