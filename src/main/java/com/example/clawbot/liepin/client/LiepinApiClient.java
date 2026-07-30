package com.example.clawbot.liepin.client;

import com.example.clawbot.liepin.config.LiepinConfig;
import com.example.clawbot.liepin.model.Application;
import com.example.clawbot.liepin.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 猎聘 MCP 客户端 — 通过 MCP Streamable HTTP 协议调用猎聘平台能力。
 *
 * <p>MCP Streamable HTTP 传输流程：
 * 1. POST endpoint → 发送 initialize 握手，获取 session ID
 * 2. POST endpoint → 发送 notifications/initialized
 * 3. POST endpoint → 发送 tools/call 调用工具</p>
 */
@Slf4j
@Component
public class LiepinApiClient {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private LiepinConfig config;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** MCP 会话是否已初始化 */
    private volatile boolean initialized = false;

    /** MCP 会话 ID（由服务器返回） */
    private volatile String sessionId = null;

    /** 是否跳过认证（token 无效时自动禁用） */
    private volatile boolean skipAuth = false;

    /**
     * 搜索职位列表。
     */
    public List<Job> searchJobs(String keyword, String city) {
        if (!config.isEnabled()) {
            log.warn("猎聘 MCP 未启用，返回空列表");
            return List.of();
        }

        try {
            // 调用 MCP 工具 search-jobs
            JsonNode result = callMcpTool("search-jobs", Map.of(
                    "jobName", keyword,
                    "address", city
            ));

            if (result == null) {
                log.warn("猎聘 MCP 搜索返回空结果");
                return List.of();
            }

            // 解析结果
            List<Job> jobs = parseJobList(result);
            log.info("猎聘搜索成功: keyword={}, 结果数={}", keyword, jobs.size());
            return jobs;

        } catch (Exception e) {
            log.error("猎聘搜索失败: keyword={}, city={}, 原因={}", keyword, city, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 获取职位详情。
     */
    public Job getJobDetail(String jobId) {
        if (!config.isEnabled()) return null;

        try {
            JsonNode result = callMcpTool("user-search-job", Map.of("jobId", jobId));
            if (result == null || result.isNull()) return null;
            List<Job> jobs = parseJobList(result);
            return jobs.isEmpty() ? null : jobs.get(0);
        } catch (Exception e) {
            log.error("猎聘职位详情获取失败: jobId={}, 原因={}", jobId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 投递简历。
     */
    public boolean applyJob(String jobId, String jobKind) {
        if (!config.isEnabled()) return false;

        try {
            JsonNode result = callMcpTool("user-apply-job", Map.of(
                    "jobId", Long.parseLong(jobId),
                    "jobKind", jobKind != null ? jobKind : "0"
            ));
            if (result == null) return false;
            log.info("猎聘投递结果: jobId={}, result={}", jobId, result);
            return true;
        } catch (Exception e) {
            log.error("猎聘投递异常: jobId={}, 原因={}", jobId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取我的简历。
     */
    public String getMyResume() {
        if (!config.isEnabled()) return null;

        try {
            JsonNode result = callMcpTool("my-resume", Map.of());
            if (result == null) return null;
            return result.toString();
        } catch (Exception e) {
            log.error("猎聘简历获取失败: 原因={}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 调用 MCP 工具 — 通过 Streamable HTTP 发送请求。
     */
    private JsonNode callMcpTool(String toolName, Map<String, Object> arguments) throws Exception {
        String endpoint = config.getEndpoint();

        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("猎聘 MCP 端点未配置");
        }

        // 第一步：如果未初始化，完成握手
        if (!initialized) {
            doInitialize(endpoint);
        }

        // 第二步：发送工具调用
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", requestId,
                "method", "tools/call",
                "params", Map.of(
                        "name", toolName,
                        "arguments", arguments
                )
        ));

        log.info("调用猎聘 MCP: tool={}, endpoint={}", toolName, endpoint);
        String responseBody = doPost(endpoint, requestBody);

        if (responseBody == null || responseBody.isBlank()) {
            log.warn("猎聘 MCP 返回空响应");
            return null;
        }

        log.info("猎聘 MCP 响应: {}", responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);

        // 检查响应是否为 HTML（错误页）
        if (responseBody.startsWith("<") || responseBody.startsWith("<!")) {
            log.error("猎聘 MCP 返回 HTML 错误页，非 JSON 响应。可能是端点或认证问题");
            return null;
        }

        JsonNode root = objectMapper.readTree(responseBody);

        if (root.has("error")) {
            log.error("猎聘 MCP 工具调用错误: {}", root.get("error"));
            return null;
        }

        if (root.has("result")) {
            return root.get("result");
        }
        return root;
    }

    /**
     * MCP 初始化 — 发送 initialize 和 notifications/initialized。
     */
    private void doInitialize(String endpoint) throws Exception {
        log.info("猎聘 MCP 初始化...");

        // 1. initialize
        String initRequestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String initBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", initRequestId,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "clawbot",
                                "version", "1.0.0"
                        )
                )
        ));

        String initResponse = doPost(endpoint, initBody);
        log.info("MCP initialize 响应: {}", initResponse != null && initResponse.length() > 300 ? initResponse.substring(0, 300) + "..." : initResponse);

        // 验证响应是有效的 JSON-RPC
        if (initResponse == null || initResponse.isBlank() || initResponse.startsWith("<")) {
            throw new RuntimeException("MCP initialize 返回无效响应（可能是 HTML 错误页）");
        }

        JsonNode initJson = objectMapper.readTree(initResponse);
        if (!initJson.has("result")) {
            throw new RuntimeException("MCP initialize 响应缺少 result: " + initResponse);
        }

        // 2. notifications/initialized
        String notifBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
        ));
        doPost(endpoint, notifBody);

        initialized = true;
        log.info("猎聘 MCP 初始化完成");
    }

    /**
     * 发送 HTTP POST 请求。遇到 401 时自动切换认证方式重试。
     */
    private String doPost(String endpoint, String body) throws Exception {
        String token = skipAuth ? null : config.getToken();
        String responseBody = doSinglePost(endpoint, body, token, true);
        int responseCode = lastResponseCode;

        // 如果 401 且有 token，尝试不带 Bearer 前缀
        if (responseCode == 401 && token != null && !token.isBlank()) {
            log.warn("MCP Bearer token 被拒绝 (401)，尝试不带 Bearer 前缀");
            responseBody = doSinglePost(endpoint, body, token, false);
            responseCode = lastResponseCode;
        }

        // 如果还是 401，去掉 token 重试
        if (responseCode == 401 && token != null && !token.isBlank()) {
            log.warn("MCP token 仍被拒绝，去掉认证重试");
            skipAuth = true;
            initialized = false;
            responseBody = doSinglePost(endpoint, body, null, false);
            responseCode = lastResponseCode;
        }

        if (responseCode == 401) {
            initialized = false;
            log.error("MCP 认证失败 (401)。请到 https://www.liepin.com/mcp/auth 重新生成凭证，" +
                    "将生成的 liepinUserToken 配置到 mcp.liepin.token");
        } else if (responseCode >= 400) {
            log.warn("MCP HTTP {}: {}", responseCode,
                    responseBody != null && responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody);
        }

        return responseBody;
    }

    /** 最近一次请求的 HTTP 状态码 */
    private int lastResponseCode = 0;

    /**
     * 发送单次 HTTP POST 请求。
     *
     * @param useBearerPrefix true 使用 "Bearer " 前缀，false 直接使用 token
     */
    private String doSinglePost(String endpoint, String body, String token, boolean useBearerPrefix) throws Exception {
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        if (token != null && !token.isBlank()) {
            String authValue = useBearerPrefix ? "Bearer " + token : token;
            conn.setRequestProperty("Authorization", authValue);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        int responseCode = conn.getResponseCode();
        lastResponseCode = responseCode;

        // 从响应头中提取 session ID
        String newSessionId = conn.getHeaderField("Mcp-Session-Id");
        if (newSessionId != null && !newSessionId.isBlank()) {
            sessionId = newSessionId;
        }

        String responseBody;
        String contentType = conn.getContentType();

        if (contentType != null && contentType.contains("text/event-stream")) {
            responseBody = readSseResponse(conn);
        } else {
            responseBody = responseCode >= 200 && responseCode < 300
                    ? readStream(conn.getInputStream())
                    : readStream(conn.getErrorStream());
        }

        conn.disconnect();
        return responseBody;
    }

    /**
     * 读取 SSE 响应，提取 data: 行的内容。
     */
    private String readSseResponse(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    sb.append(line.substring(6));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 读取输入流。
     */
    private String readStream(java.io.InputStream is) {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析职位列表 — 兼容多种 MCP 响应结构。
     */
    private List<Job> parseJobList(JsonNode result) {
        List<Job> jobs = new ArrayList<>();

        // 尝试从 content 数组中提取（MCP 标准响应格式）
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText("");
                if ("text".equals(type)) {
                    String text = item.path("text").asText("");
                    try {
                        JsonNode textNode = objectMapper.readTree(text);
                        // 如果 text 是一个数组，遍历每个元素
                        if (textNode.isArray()) {
                            for (JsonNode jobNode : textNode) {
                                jobs.add(parseJob(jobNode));
                            }
                        } else {
                            jobs.add(parseJob(textNode));
                        }
                    } catch (Exception e) {
                        log.debug("解析 MCP text 内容失败: {}", text);
                    }
                }
            }
            if (!jobs.isEmpty()) return jobs;
        }

        // 尝试直接解析 data.list
        JsonNode dataList = result.path("data").path("list");
        if (dataList.isArray()) {
            for (JsonNode node : dataList) {
                jobs.add(parseJob(node));
            }
            return jobs;
        }

        // 尝试 result 字段
        JsonNode resultNode = result.path("result");
        if (resultNode.isArray()) {
            for (JsonNode node : resultNode) {
                jobs.add(parseJob(node));
            }
            return jobs;
        }

        // 尝试 jobs 字段
        JsonNode jobsNode = result.path("jobs");
        if (jobsNode.isArray()) {
            for (JsonNode node : jobsNode) {
                jobs.add(parseJob(node));
            }
            return jobs;
        }

        return jobs;
    }

    private Job parseJob(JsonNode node) {
        return Job.builder()
                .jobId(node.path("jobId").asText(node.path("job_id").asText(node.path("jobNo").asText(null))))
                .title(node.path("title").asText(node.path("jobName").asText(null)))
                .company(node.path("company").asText(node.path("compName").asText(null)))
                .city(node.path("city").asText(node.path("dq").asText(node.path("address").asText(null))))
                .salary(node.path("salary").asText(node.path("salaryDesc").asText(null)))
                .experience(node.path("experience").asText(node.path("workExperience").asText(null)))
                .education(node.path("education").asText(node.path("eduLevel").asText(null)))
                .description(node.path("description").asText(node.path("jobDesc").asText(null)))
                .url(node.path("url").asText(null))
                .build();
    }

    private Application parseApplication(JsonNode node) {
        return Application.builder()
                .applicationId(node.path("applicationId").asText(null))
                .jobId(node.path("jobId").asText(null))
                .jobTitle(node.path("jobTitle").asText(null))
                .company(node.path("company").asText(null))
                .status(node.path("status").asText(null))
                .applyTime(node.path("applyTime").asText(null))
                .build();
    }
}
