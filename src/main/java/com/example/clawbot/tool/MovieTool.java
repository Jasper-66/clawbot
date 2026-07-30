package com.example.clawbot.tool;

import com.example.clawbot.service.MovieService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MovieTool {

    private final MovieService movieService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "search_movie";
    private static final String DESCRIPTION = "查询电影信息。支持：按关键词搜索电影、按电影名称查看详情、按IMDB ID查看详情。当用户询问电影、热映、评分、推荐电影、演员、导演等信息时使用此工具。";

    public String getToolName() {
        return NAME;
    }

    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "type", Map.of(
                                                "type", "string",
                                                "description", "查询类型：search（搜索电影）、title（按名称查看详情）、id（按IMDB ID查看详情）",
                                                "enum", List.of("search", "title", "id")
                                        ),
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "搜索关键词或电影名称（type=search或title时必填）"
                                        ),
                                        "imdb_id", Map.of(
                                                "type", "string",
                                                "description", "IMDB ID（type=id时必填，格式如 tt0111161）"
                                        )
                                ),
                                "required", List.of("type")
                        )
                )
        );
    }

    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String type = arguments.path("type").asText("").trim();

            if (type.isEmpty()) {
                return "工具调用失败：type 参数不能为空";
            }

            log.info("执行电影工具: type={}", type);

            switch (type) {
                case "search":
                    String query = arguments.path("query").asText("").trim();
                    if (query.isEmpty()) {
                        return "工具调用失败：搜索电影时 query 参数不能为空";
                    }
                    return movieService.searchMovie(query, 1);
                case "title":
                    String title = arguments.path("query").asText("").trim();
                    if (title.isEmpty()) {
                        return "工具调用失败：查询电影时 query 参数不能为空";
                    }
                    return movieService.getMovieByTitle(title);
                case "id":
                    String imdbId = arguments.path("imdb_id").asText("").trim();
                    if (imdbId.isEmpty()) {
                        return "工具调用失败：查询电影详情时 imdb_id 参数不能为空";
                    }
                    return movieService.getMovieById(imdbId);
                default:
                    return "工具调用失败：不支持的查询类型 " + type;
            }
        } catch (Exception e) {
            log.error("电影工具执行失败: {}", e.getMessage());
            return "工具调用失败：arguments 不是有效的 JSON";
        }
    }
}
