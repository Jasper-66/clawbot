package com.example.clawbot.liepin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 投递记录模型 — 对应猎聘 API 返回的简历投递状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Application {

    /** 投递记录 ID */
    private String applicationId;

    /** 职位 ID */
    private String jobId;

    /** 职位名称 */
    private String jobTitle;

    /** 公司名称 */
    private String company;

    /**
     * 投递状态：
     * - APPLIED: 已投递
     * - VIEWED: 已被查看
     * - INTERVIEW: 面试邀请
     * - REJECTED: 不合适
     */
    private String status;

    /** 投递时间 */
    private String applyTime;
}
