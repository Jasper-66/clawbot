package com.example.clawbot.movie.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// 购票订单
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrder {
    /** 订单ID */
    private String orderId;
    /** 用户ID */
    private String userId;
    /** 电影信息 */
    private Movie movie;
    /** 影院信息 */
    private Cinema cinema;
    /** 场次信息 */
    private Showtime showtime;
    /** 已选座位列表 */
    private List<Seat> seats;
    /** 总价 */
    private Double totalPrice;
    /** 订单状态: CREATED / PAID / CANCELLED / FAILED */
    private String status;
    /** 取票码 */
    private String ticketCode;
    /** 下单时间 */
    private String createdAt;
}
