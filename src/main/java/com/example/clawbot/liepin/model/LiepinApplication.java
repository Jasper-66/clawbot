package com.example.clawbot.liepin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiepinApplication {
    private String id;              // 本地记录ID
    private String jobId;           // 猎聘岗位ID
    private String jobKind;         // 职位类型编号
    private String jobTitle;        // 职位名称
    private String company;         // 公司名称
    private String city;            // 城市
    private String salary;          // 薪资
    private String status;          // 已投递/失败
    private LocalDateTime appliedAt; // 投递时间
    private String errorMessage;    // 失败原因（如果投递失败）
}
