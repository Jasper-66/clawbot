package com.example.clawbot.movie.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 座位信息
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Seat {
    /** 座位ID（平台侧） */
    private String seatId;
    /** 排 */
    private Integer row;
    /** 列号 */
    private Integer column;
    /** 座位名称，如 5排12座 */
    private String name;
    /** 状态（预售用）: AVAILABLE / LOCKED / SOLD */
    private String status;
    /** 该座位单价 */
    private Double price;
}
