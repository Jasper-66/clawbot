package com.example.clawbot.movie.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 影院信息
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cinema {
    /** 影院ID（平台侧） */
    private String cinemaId;
    /** 影院名称 */
    private String name;
    /** 地址 */
    private String address;
    /** 经纬度 */
    private Double longitude;
    private Double latitude;
    /** 距离用户（米），需外部计算填入 */
    private Integer distance;
}
