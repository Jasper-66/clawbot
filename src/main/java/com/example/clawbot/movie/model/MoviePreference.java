package com.example.clawbot.movie.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// LLM 解析后的用户偏好（从自然语言中提取的结构化需求）
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoviePreference {
    /** 用户原始输入 */
    private String rawInput;
    /** 想看的电影名称/关键词（可为空=不限） */
    private String movieKeyword;
    /** 期望日期，如 2026-08-01（可为空=今天/不限） */
    private String date;
    /** 期望时间段: MORNING / AFTERNOON / EVENING / NIGHT */
    private String timeRange;
    /** 购票数量 */
    private Integer quantity;
    /** 城市 */
    private String city;
    /** 期望影院名称/区域（可为空=不限） */
    private String locationPreference;
    /** 最低评分（可为空=不限） */
    private Double minRating;
}
