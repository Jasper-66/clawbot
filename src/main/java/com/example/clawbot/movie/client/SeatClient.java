package com.example.clawbot.movie.client;

import com.example.clawbot.movie.model.Seat;

import java.util.List;

// ── 成员4: 座位管理 ──
// 职责：对接平台API，获取座位图、锁定座位、释放座位
public interface SeatClient {

    /**
     * 获取指定场次的实时座位图。
     *
     * 【被谁调用】MovieTicketOrchestrator.autoPurchase() 的第四步
     * 【返回值】  List<Seat>（全部座位），每个 Seat.status ∈ {AVAILABLE, LOCKED, SOLD}
     *
     * 【实现流程】
     *   1. GET /seats?showtimeId=xxx
     *   2. 解析 JSON 二维座位数组 → 拍平为 List<Seat>
     *   3. 每个 Seat 填充：seatId, row, column, name（如"5排12座"）, status, price
     *   4. 标注不同价格区（如中间黄金区贵10元）
     *   5. 若场次不存在 → 抛 BusinessException("场次已过期")
     */
    List<Seat> getSeats(String showtimeId);

    /**
     * 锁定指定座位（原子操作，超时自动释放）。
     *
     * 【被谁调用】Member6 OrderClient.createOrder() 的第一步
     * 【返回值】  lockToken（字符串令牌），凭此令牌创建订单；失败返回 null
     *
     * 【实现流程】
     *   1. POST /seats/lock  body: {showtimeId, seatIds: [...], timeout: 300s}
     *   2. 平台返回 lockToken + 过期时间
     *   3. 若任一座位已被占 → 返回 null，上层重新选座
     *   4. 锁定期内未支付 → 平台自动释放（300秒）
     */
    String lockSeats(String showtimeId, List<String> seatIds);

    /**
     * 主动释放之前锁定的座位。
     *
     * 【被谁调用】Member7 在用户取消或支付失败时调用
     * 【返回值】  true=释放成功
     *
     * 【实现流程】
     *   1. POST /seats/unlock  body: {lockToken}
     *   2. 返回操作结果
     */
    boolean unlockSeats(String lockToken);
}
