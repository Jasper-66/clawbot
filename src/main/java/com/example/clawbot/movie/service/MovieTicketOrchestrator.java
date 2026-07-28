package com.example.clawbot.movie.service;

import com.example.clawbot.movie.model.*;

import java.util.List;

// ── 成员7: 全流程编排（指挥家）──
// 职责：串联成员1~6的所有模块，处理用户交互流程（确认/重选/取消），提供统一入口
// 这是整个电影票模块的"大脑"，其他6个成员都是被它调用的"手脚"
public interface MovieTicketOrchestrator {

    // ═══════════════════════════════════════════════════
    // 方法1: 搜索电影 — 用户问"有什么好看的电影"
    // ═══════════════════════════════════════════════════
    /**
     * 搜索当前上映的电影列表。
     *
     * 【被谁调用】MovieTicketTool.searchMovies() → LLM Function Calling
     * 【返回值】  格式化的电影列表文本，可直接发给用户（含片名、评分、海报简述）
     *
     * 【实现流程】
     *   1. 从 MoviePreferenceParser 获取用户城市偏好（或默认城市）
     *   2. 调用 Member2 MovieSearchClient.searchMovies(city, keyword)
     *   3. 若结果为空 → 返回"当前城市未找到匹配电影，请换个关键词试试"
     *   4. 按评分降序取前5部，格式化为微信可读文本：
     *      "🎬 为您找到以下电影：\n1. 《哪吒3》⭐8.5 动画/奇幻\n2. ..."
     *   5. 返回格式化文本 → LLM → 发送给用户
     */
    String searchMovies(String userId, String city, String keyword);

    // ═══════════════════════════════════════════════════
    // 方法2: 一键自动购票（核心方法）
    // ═══════════════════════════════════════════════════
    /**
     * 全自动购票：解析意图 → 搜电影 → 搜场次 → 选座 → 下单支付。
     *
     * 【被谁调用】MovieTicketTool.purchaseTicket() → LLM Function Calling
     * 【返回值】  TicketOrder 含取票码；失败时抛异常由Tool层捕获后返回错误文本
     *
     * 【实现流程（串联6个成员）】
     *   Step1 — 调 Member1 MoviePreferenceParser.parse(userId, message) → MoviePreference
     *   Step2 — 调 Member2 MovieSearchClient.searchMovies(city, keyword) → List<Movie>
     *           若匹配到唯一电影 → 直接用；多个匹配 → 取评分最高的；无匹配 → 抛异常提示
     *   Step3 — 调 Member3 ShowtimeClient.getShowtimes(movieId, city, date) → List<Showtime>
     *           按 MoviePreference.timeRange 过滤时段，取最早的满足条件的场次
     *           若无满足条件 → 抛异常"该电影在您要求的时间段无场次"
     *   Step4 — 调 Member4 SeatClient.getSeats(showtimeId) → List<Seat>
     *           过滤 status=AVAILABLE
     *   Step5 — 调 Member5 SeatSelector.select(availableSeats, quantity) → List<Seat>
     *           若返回空 → 抛异常"可选座位不足" + 建议换场次
     *   Step6 — 调 Member4 SeatClient.lockSeats(showtimeId, selectedSeatIds) → lockToken
     *           若锁座失败（并发冲突）→ 回Step5重选（最多重试3次）
     *   Step7 — 调 Member6 OrderClient.createOrder(...) → TicketOrder(CREATED)
     *           → 调 Member6 OrderClient.payOrder(orderId) → TicketOrder(PAID)
     *   Step8 — 格式化返回："✅ 购票成功！\n🎬 《哪吒3》\n🏛 万达影城(国贸店)\n..."
     */
    TicketOrder autoPurchase(String userId, String userMessage);

    // ═══════════════════════════════════════════════════
    // 方法3: 查询场次 — 用户选好电影后看有哪些时间可选
    // ═══════════════════════════════════════════════════
    /**
     * 查询指定电影的可选场次。
     *
     * 【被谁调用】MovieTicketTool.searchShowtimes() → LLM Function Calling
     * 【返回值】  格式化的场次列表文本
     *
     * 【实现流程】
     *   1. 调用 Member3 ShowtimeClient.getShowtimes(movieId, city, date)
     *   2. 按影院分组，格式化输出：
     *      "📍 万达影城(国贸店) 距离2.3km\n  14:30 IMAX厅 ¥49 | 19:00 4号厅 ¥39\n..."
     *   3. 返回格式化文本
     */
    String searchShowtimes(String movieId, String city, String date);

    // ═══════════════════════════════════════════════════
    // 内部辅助方法
    // ═══════════════════════════════════════════════════

    /**
     * 向用户发送确认消息（微信交互式确认）。
     *
     * 【被谁调用】autoPurchase() 中 Step2 完成后、下单前
     * 【返回值】  true=用户确认 / false=用户取消
     *
     * 【实现流程】
     *   1. 构建确认文本："即将为您购买：\n🎬 《哪吒3》\n⏰ 8月1日 19:30..."
     *   2. 通过 WeChatBotService 发送给用户，等待回复"确认"或"取消"
     *   3. 需实现等待用户回复的机制（可复用现有的消息处理循环）
     */
    boolean requestUserConfirmation(String userId, TicketOrder preview);
}
