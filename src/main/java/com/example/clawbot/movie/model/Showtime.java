package com.example.clawbot.movie.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 场次信息：哪部电影 + 哪个影院 + 几点 + 什么厅
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Showtime {
    /** 场次ID（平台侧） */
    private String showtimeId;
    /** 电影ID */
    private String movieId;
    /** 影院ID */
    private String cinemaId;
    /** 放映日期，如 2026-08-01 */
    private String showDate;
    /** 放映时间，如 19:30 */
    private String showTime;
    /** 影厅，如 IMAX厅 / 4号厅 */
    private String hall;
    /** 原价 */
    private Double originalPrice;
    /** 平台售价 */
    private Double price;
    /** 语言版本，如 国语2D / 英语3D */
    private String version;
}
