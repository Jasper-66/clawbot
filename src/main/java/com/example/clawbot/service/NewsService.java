package com.example.clawbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private final RestTemplate restTemplate;

    private static final String API_BASE = "https://60s.viki.moe/v2";

    private static final Map<String, String> SOURCE_NAMES = Map.of(
            "weibo",   "微博热搜",
            "toutiao", "头条热榜",
            "zhihu",   "知乎热榜",
            "baidu",   "百度热搜",
            "douyin",  "抖音热榜",
            "60s",     "每日60秒新闻"
    );

    public String getNews(String source, int limit) {
        if (!SOURCE_NAMES.containsKey(source)) {
            return "不支持的新闻来源：" + source + "，支持的来源有：" + String.join("、", SOURCE_NAMES.keySet());
        }

        limit = Math.max(1, Math.min(limit, 30));

        try {
            String endpoint = source.equals("baidu") ? "baidu/hot" : source;
            String url = API_BASE + "/" + endpoint;
            log.info("查询新闻: source={}, limit={}", source, limit);
            String response = restTemplate.getForObject(url, String.class);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);

            int code = root.path("code").asInt(0);
            if (code != 200) {
                String errMsg = root.path("message").asText("未知错误");
                log.error("新闻 API 返回错误: {}", errMsg);
                return "查询新闻失败：" + errMsg;
            }

            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return "暂无「" + SOURCE_NAMES.get(source) + "」的新闻数据。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📰 ").append(SOURCE_NAMES.get(source)).append(" 热榜\n\n");

            int count = Math.min(limit, data.size());
            for (int i = 0; i < count; i++) {
                JsonNode item = data.get(i);
                String title = item.path("title").asText("");
                String hot = item.path("hot_value").asText("");
                String link = item.path("link").asText("");

                sb.append(i + 1).append(". ").append(title);
                if (!hot.isEmpty() && !hot.equals("0")) {
                    sb.append(" (").append(hot).append(")");
                }
                if (!link.isEmpty()) {
                    sb.append("\n   ").append(link);
                }
                sb.append("\n");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.error("查询新闻失败, source={}", source, e);
            return "抱歉，查询「" + SOURCE_NAMES.getOrDefault(source, source) + "」新闻失败，请稍后再试。";
        }
    }
}
