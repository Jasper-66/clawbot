package com.example.clawbot.movie.service;

import com.example.clawbot.movie.model.MoviePreference;

// ── 成员1: 意图解析 ──
// 职责：接收微信用户的自然语言消息，调用LLM将自由文本转为结构化购票偏好
public interface MoviePreferenceParser {

    /**
     * 解析用户自然语言 → 结构化购票偏好。
     *
     * 【被谁调用】MovieTicketOrchestrator.autoPurchase() 的第一步
     * 【返回值】  MoviePreference（包含电影关键词、日期、时间段、数量、城市等）
     *
     * 【实现流程】
     *   1. 构建提示词：要求 LLM 从用户消息中提取 {电影名, 日期, 时段, 数量, 城市, 位置偏好, 最低评分}
     *   2. 调用 LlmService.chat() 或直接调 DeepSeek API，temperature=0.1 保证稳定提取
     *   3. 解析 LLM 返回的 JSON，反序列化为 MoviePreference 对象
     *   4. 缺失字段填默认值：城市=配置文件默认城市, 日期=今天, 数量=1
     *   5. 返回 MoviePreference
     */
    MoviePreference parse(String userId, String userMessage);
}
