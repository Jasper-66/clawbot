package com.example.clawbot.movie.client;

import com.example.clawbot.movie.model.Movie;

import java.util.List;

// ── 成员2: 电影搜索 ──
// 职责：对接外部电影票平台API，实现电影搜索和详情查询
public interface MovieSearchClient {

    /**
     * 搜索正在上映的电影列表。
     *
     * 【被谁调用】MovieTicketOrchestrator.autoPurchase() 的第二步
     * 【返回值】  List<Movie>，按评分/热度降序；无结果返回空列表
     *
     * 【实现流程】
     *   1. 构建 HTTP GET 请求：平台 API 的 /movies 端点，参数 city + keyword
     *   2. 使用 RestTemplate 发送请求，设置 API Key 到 Header
     *   3. 解析 JSON 响应 → 提取电影ID、标题、评分、海报URL等字段
     *   4. 映射为 List<Movie> 并返回
     *   5. 平台返回空或超时 → 返回空列表，不抛异常（让上层决定降级策略）
     */
    List<Movie> searchMovies(String city, String keyword);

    /**
     * 获取单部电影详情。
     *
     * 【被谁调用】MovieTicketOrchestrator.confirmSelection() 展示电影信息给用户确认
     * 【返回值】  Movie 详情，不存在返回 null
     *
     * 【实现流程】
     *   1. GET /movies/{movieId}，附 API Key
     *   2. 解析响应 → Movie 对象
     *   3. 若 404 → 返回 null
     */
    Movie getMovieDetail(String movieId);
}
