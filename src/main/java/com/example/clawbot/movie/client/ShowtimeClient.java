package com.example.clawbot.movie.client;

import com.example.clawbot.movie.model.Cinema;
import com.example.clawbot.movie.model.Showtime;

import java.util.List;

// ── 成员3: 场次与影院查询 ──
// 职责：对接平台API，查询影院列表和电影场次信息
public interface ShowtimeClient {

    /**
     * 获取指定电影在指定城市的放映场次。
     *
     * 【被谁调用】MovieTicketOrchestrator.autoPurchase() 的第三步
     * 【返回值】  List<Showtime>，按时间升序；无场次返回空列表
     *
     * 【实现流程】
     *   1. GET /showtimes?movieId=xxx&city=xxx&date=2026-08-01
     *   2. 解析 JSON 数组 → 每个元素包含 showtimeId, cinemaId, showDate, showTime, hall, price, version
     *   3. 映射为 List<Showtime>，计算 price 字段（平台价 vs 原价）
     *   4. 如果 date 未指定，默认查今天+明天两天
     *   5. 按 showTime 升序排列后返回
     */
    List<Showtime> getShowtimes(String movieId, String city, String date);

    /**
     * 搜索用户附近的影院。
     *
     * 【被谁调用】MovieTicketOrchestrator 中按位置筛选影院时调用
     * 【返回值】  List<Cinema>，按距离升序
     *
     * 【实现流程】
     *   1. GET /cinemas?lat=39.9&lng=116.4&radius=5000（半径5km）
     *   2. 或使用已有的高德地图 search_nearby 工具间接获取
     *   3. 解析响应 → 填充 Cinema 对象（含经纬度、地址）
     *   4. 按 distance 升序排列
     */
    List<Cinema> getNearbyCinemas(double latitude, double longitude, int radiusMeters);
}
