package com.example.clawbot.liepin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiepinJob {
    private String jobId;           // 猎聘岗位ID（投递时需要）
    private String jobKind;         // 职位类型编号，必须是"1"或"2"（投递时必填）
    private String title;           // 职位名称
    private String company;         // 公司名称
    private String city;            // 城市
    private String salary;          // 薪资范围
    private String experience;      // 经验要求
    private String education;       // 学历要求
    private String applyUrl;        // 投递链接
}
