package com.example.clawbot.resume.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 单次投递结果
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationResult {
    /** 投递是否成功 */
    private boolean success;
    /** 关联的岗位信息 */
    private JobListing jobListing;
    /** 平台返回的投递ID */
    private String applicationId;
    /** 投递状态: SUBMITTED / VIEWED / INTERVIEW / REJECTED */
    private String status;
    /** 平台返回的消息 */
    private String message;
    /** 投递时间 */
    private String appliedAt;
    /** 匹配评分（0~100） */
    private Integer matchScore;
    /** 简历优化建议（AI生成的针对该岗位的简历调整建议） */
    private String optimizationTip;
    /** 简历摘要（拉取到的简历关键信息，逐行展示） */
    private String resumeSummary;
    /** 投递方式: MCP_API / COOKIE_API / MOCK（模拟） */
    private String deliveryMethod;
    /** 是否真实投递成功（Mock模式为false） */
    private boolean realSuccess;
}
