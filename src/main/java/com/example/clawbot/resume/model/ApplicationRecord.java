package com.example.clawbot.resume.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 投递记录（持久化存储，用于统计和追踪）
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationRecord {
    /** 记录ID */
    private String recordId;
    /** 用户ID */
    private String userId;
    /** 岗位名称 */
    private String jobTitle;
    /** 公司名称 */
    private String company;
    /** 薪资范围 */
    private String salary;
    /** 工作城市 */
    private String city;
    /** 投递状态: SUBMITTED / VIEWED / INTERVIEW / REJECTED / OFFER */
    private String status;
    /** 匹配评分 */
    private Integer matchScore;
    /** 投递时间 */
    private String appliedAt;
    /** 最后更新时间 */
    private String updatedAt;
    /** 备注（用户可添加） */
    private String remark;
}
