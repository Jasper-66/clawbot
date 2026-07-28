package com.example.clawbot.movie.service;

import com.example.clawbot.movie.model.Seat;

import java.util.List;

// ── 成员5: 自动选座算法 ──
// 职责：纯算法模块，不调任何API。从可用座位中选出最优组合
public interface SeatSelector {

    /**
     * 从可用座位中自动选出最佳的 N 个座位。
     *
     * 【被谁调用】MovieTicketOrchestrator.autoPurchase() 的第五步
     * 【返回值】  List<Seat>，数量 = quantity；可选座位不足时返回空列表
     *
     * 【实现流程（按优先级依次尝试）】
     *   1. 过滤：只保留 status=AVAILABLE 的座位
     *   2. 策略A — 中心优选：从影厅中间列向两侧扩散评分，给每个座位打分
     *      - 列靠近中轴线 → +3分
     *      - 排在黄金排（总排数*0.4 ~ *0.7）→ +5分
     *      - 距离已选座位的中心越近 → +2分
     *   3. 策略B — 连座检测：在同排中找连续 quantity 个可用座位
     *      - 滑动窗口扫描每排，找到第一个满足数量且总分最高的连续段
     *   4. 策略C — 分散降级：连座找不到时，取评分最高的 quantity 个座位（不要求连续）
     *   5. 策略D — 无法满足：可选座位 < quantity → 返回空列表
     *   6. 返回最终选中的 List<Seat>
     *
     * 【示例】买2张 → 优先找"9排6座+9排7座"（中间连座），找不到则取评分最高2个
     */
    List<Seat> select(List<Seat> allSeats, int quantity);
}
