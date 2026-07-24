package com.example.clawbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class MovieService {

    private final RestTemplate restTemplate;

    @Value("${omdb.api.key}")
    private String apiKey;

    private static final String API_BASE = "http://www.omdbapi.com/";

    public String searchMovie(String query, int page) {
        try {
            String url = API_BASE + "?apikey=" + apiKey + "&s=" + query + "&page=" + page + "&type=movie";
            log.info("搜索电影: query={}", query);
            String response = restTemplate.getForObject(url, String.class);
            return parseSearchResult(response, query);
        } catch (Exception e) {
            log.error("搜索电影失败, query={}", query, e);
            return "抱歉，搜索电影失败，请稍后再试。";
        }
    }

    public String getMovieByTitle(String title) {
        try {
            String url = API_BASE + "?apikey=" + apiKey + "&t=" + title + "&plot=full";
            log.info("查询电影: title={}", title);
            String response = restTemplate.getForObject(url, String.class);
            return parseMovieDetail(response);
        } catch (Exception e) {
            log.error("查询电影失败, title={}", title, e);
            return "抱歉，查询电影失败，请稍后再试。";
        }
    }

    public String getMovieById(String imdbId) {
        try {
            String url = API_BASE + "?apikey=" + apiKey + "&i=" + imdbId + "&plot=full";
            log.info("查询电影详情: imdbId={}", imdbId);
            String response = restTemplate.getForObject(url, String.class);
            return parseMovieDetail(response);
        } catch (Exception e) {
            log.error("查询电影详情失败, imdbId={}", imdbId, e);
            return "抱歉，查询电影详情失败，请稍后再试。";
        }
    }

    private String parseSearchResult(String json, String query) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            if (root.has("Error")) {
                String error = root.path("Error").asText("");
                if (error.contains("Movie not found")) {
                    return "未找到与「" + query + "」相关的电影。";
                }
                return "搜索失败：" + error;
            }

            JsonNode results = root.path("Search");
            int totalResults = root.path("totalResults").asInt(0);

            if (!results.isArray() || results.isEmpty()) {
                return "未找到与「" + query + "」相关的电影。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🎬 搜索结果（共 ").append(totalResults).append(" 部）\n\n");

            int count = Math.min(5, results.size());
            for (int i = 0; i < count; i++) {
                JsonNode movie = results.get(i);
                String title = movie.path("Title").asText("");
                String year = movie.path("Year").asText("未知");
                String imdbId = movie.path("imdbID").asText("");

                sb.append(i + 1).append(". ").append(title);
                sb.append(" (").append(year).append(")");
                sb.append(" [ID: ").append(imdbId).append("]\n");
            }

            sb.append("\n💡 发送「电影详情 + ID」可查看详细信息");
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("解析搜索结果失败", e);
            return "解析电影数据失败。";
        }
    }

    private String parseMovieDetail(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode movie = mapper.readTree(json);

            if (movie.has("Error")) {
                String error = movie.path("Error").asText("");
                if (error.contains("Movie not found")) {
                    return "未找到该电影。";
                }
                return "查询失败：" + error;
            }

            String title = movie.path("Title").asText("");
            String year = movie.path("Year").asText("未知");
            String rated = movie.path("Rated").asText("未知");
            String released = movie.path("Released").asText("未知");
            String runtime = movie.path("Runtime").asText("未知");
            String genre = movie.path("Genre").asText("未知");
            String director = movie.path("Director").asText("未知");
            String actors = movie.path("Actors").asText("未知");
            String plot = movie.path("Plot").asText("暂无简介");
            String language = movie.path("Language").asText("未知");
            String country = movie.path("Country").asText("未知");
            String awards = movie.path("Awards").asText("无");

            JsonNode ratings = movie.path("Ratings");
            String imdbRating = movie.path("imdbRating").asText("未知");
            String imdbVotes = movie.path("imdbVotes").asText("未知");
            String boxOffice = movie.path("BoxOffice").asText("未知");

            StringBuilder sb = new StringBuilder();
            sb.append("🎬 ").append(title).append(" (").append(year).append(")\n\n");

            sb.append("📊 评分：").append(imdbRating).append("/10（IMDb ").append(imdbVotes).append(" 票）\n");
            sb.append("📅 上映日期：").append(released).append("\n");
            sb.append("⏱️ 片长：").append(runtime).append("\n");
            sb.append("🎭 类型：").append(genre).append("\n");
            sb.append("🎬 导演：").append(director).append("\n");
            sb.append("👥 主演：").append(actors).append("\n");
            sb.append("🌍 国家：").append(country).append("\n");
            sb.append("🗣️ 语言：").append(language).append("\n");
            sb.append("🏆 分级：").append(rated).append("\n");

            if (!awards.equals("N/A") && !awards.isEmpty()) {
                sb.append("🥇 奖项：").append(awards).append("\n");
            }

            if (!boxOffice.equals("N/A") && !boxOffice.isEmpty()) {
                sb.append("💰 票房：").append(boxOffice).append("\n");
            }

            if (ratings.isArray() && ratings.size() > 0) {
                sb.append("\n📈 各平台评分：\n");
                for (JsonNode rating : ratings) {
                    String source = rating.path("Source").asText("");
                    String value = rating.path("Value").asText("");
                    sb.append("   - ").append(source).append("：").append(value).append("\n");
                }
            }

            sb.append("\n📖 简介：\n").append(plot);

            return sb.toString();
        } catch (Exception e) {
            log.error("解析电影详情失败", e);
            return "解析电影数据失败。";
        }
    }
}
