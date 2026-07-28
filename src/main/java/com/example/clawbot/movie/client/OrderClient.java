package com.example.clawbot.movie.client;

import com.example.clawbot.movie.model.TicketOrder;

import java.util.List;

// ── 成员6: 下单与支付 ──
// 职责：对接平台API，创建订单、执行支付、查询订单状态
public interface OrderClient {

    /**
     * 创建订单（预下单，此时座位已被Member4锁定）。
     *
     * 【被谁调用】MovieTicketOrchestrator.placeOrder()
     * 【返回值】  TicketOrder（status=CREATED，含订单ID和总价）
     *
     * 【实现流程】
     *   1. POST /orders  body: {showtimeId, seatIds, lockToken, userId}
     *   2. 平台验证 lockToken 有效且未过期
     *   3. 平台返回 orderId + totalPrice + 过期时间（通常15分钟内需支付）
     *   4. 构建 TicketOrder 对象，填充所有字段
     *   5. 若平台返回错误（如座位已被他人锁定）→ 抛 BusinessException
     */
    TicketOrder createOrder(String showtimeId, List<String> seatIds, String lockToken, String userId);

    /**
     * 支付订单。
     *
     * 【被谁调用】MovieTicketOrchestrator.placeOrder() 中 createOrder 成功后调用
     * 【返回值】  TicketOrder（status=PAID，含取票码）
     *
     * 【实现流程】
     *   1. POST /orders/{orderId}/pay
     *   2. 平台扣款 → 返回支付成功 + ticketCode（取票二维码/数字串）
     *   3. 更新 TicketOrder.status = PAID, ticketCode = 平台返回的取票码
     *   4. 支付失败处理：
     *      - 余额不足 → 返回 status=PAY_FAILED，不释放座位（等用户重试）
     *      - 超时 → 座位已被释放 → 提示用户重新选座
     */
    TicketOrder payOrder(String orderId);

    /**
     * 查询订单当前状态。
     *
     * 【被谁调用】用户询问"我的票怎么样了"时，由 Member7 调用
     * 【返回值】  TicketOrder 当前状态
     *
     * 【实现流程】
     *   1. GET /orders/{orderId}
     *   2. 返回最新 order 状态（CREATED / PAID / CANCELLED / REFUNDED）
     */
    TicketOrder getOrderStatus(String orderId);
}
