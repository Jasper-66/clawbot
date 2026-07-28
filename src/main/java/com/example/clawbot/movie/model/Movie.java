package com.example.clawbot.movie.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 电影信息
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Movie {
    /** 电影ID（平台侧） */
    private String movieId;
    /** 电影名称 */
    private String title;
    /** 导演 */
    private String director;
    /** 主演，逗号分隔 */
    private String cast;
    /** 评分，如 8.5 */
    private Double rating;
    /** 海报图片URL */
    private String posterUrl;
    /** 上映日期 */
    private String releaseDate;
    /** 影片时长（分钟） */
    private Integer duration;
    /** 类型，如 动作/科幻 */
    private String genre;
}
