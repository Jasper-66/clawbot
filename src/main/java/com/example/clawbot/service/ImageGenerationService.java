package com.example.clawbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;

/** 图片生成服务，调用智谱 CogView-3-Plus API 根据文本描述生成图片。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${image.gen.api.key}")
    private String apiKey;

    @Value("${image.gen.api.base-url}")
    private String baseUrl;

    @Value("${image.gen.api.model}")
    private String model;

    @Value("${image.gen.api.endpoint}")
    private String endpoint;

    /** 根据文本描述生成图片，兼容 OpenAI 和 DashScope 响应格式。 */
    public byte[] generateImage(String prompt) {
        try {
            // --- 1. 构建 HTTP 请求头 ---
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);   // Content-Type: application/json
            headers.setBearerAuth(apiKey);                        // Authorization: Bearer <key>

            // --- 2. 构建请求体 ---
            // 使用 Map.of() 创建不可变 Map，结构对应 OpenAI 兼容的 images API 格式
            Map<String, Object> requestBody = Map.of(
                    "model", model,            // 模型名称
                    "prompt", prompt,          // 图片描述
                    "size", "1024x1024",       // 输出尺寸
                    "n", 1                     // 生成数量
            );

            // --- 3. 序列化并发送 POST 请求 ---
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.info("图片生成请求: model={}, prompt={}", model, prompt);

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            // 完整 URL = baseUrl + endpoint，例如 https://open.bigmodel.cn/api/paas/v4/images/generations
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + endpoint, entity, String.class);

            // --- 4. 解析响应 ---
            String responseBody = response.getBody();
            log.info("图片生成响应: {}", responseBody);

            if (responseBody == null) {
                throw new RuntimeException("图片生成API返回空响应");
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // --- 4a. 检查 OpenAI 格式错误: {"error": {"message": "..."}} ---
            // 例如额度不足、参数错误时会返回此格式
            if (root.has("error")) {
                String errorMsg = root.get("error").toString();
                log.error("API错误(OpenAI格式): {}", errorMsg);
                throw new RuntimeException("图片生成失败: " + errorMsg);
            }

            // --- 4b. 检查 DashScope 格式错误: {"code": "xxx", "message": "..."} ---
            // DashScope 用 code 字段表示错误码，code 为空或 "0" 表示正常
            if (root.has("code")) {
                String code = root.path("code").asText("");
                String msg = root.path("message").asText("未知错误");
                if (!"0".equals(code) && !"".equals(code)) {
                    log.error("API错误(DashScope格式): code={}, message={}", code, msg);
                    throw new RuntimeException("图片生成失败 [" + code + "]: " + msg);
                }
            }

            // --- 4c. 尝试 OpenAI 成功格式: {"data": [{"url": "..."}]} ---
            // data 是一个数组，每次生成 n 张图，这里 n=1 所以取 data[0]
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && data.size() > 0) {
                JsonNode first = data.get(0);

                // 部分 API 直接返回 base64 编码的图片数据（b64_json），无需二次下载
                if (first.has("b64_json")) {
                    String b64 = first.get("b64_json").asText();
                    log.info("图片生成成功(base64): 长度={}", b64.length());
                    return Base64.getDecoder().decode(b64);
                }

                // 标准 OpenAI 格式：返回临时下载 URL，需要再次请求下载
                if (first.has("url")) {
                    String imageUrl = first.get("url").asText();
                    return downloadFromUrl(imageUrl);
                }
            }

            // --- 4d. 尝试 DashScope 成功格式: {"output": {"results": [{"url": "..."}]}} ---
            // DashScope 原生 API 的异步任务结果格式
            JsonNode output = root.get("output");
            if (output != null) {
                JsonNode results = output.get("results");
                if (results != null && results.isArray() && results.size() > 0) {
                    String imageUrl = results.get(0).get("url").asText();
                    return downloadFromUrl(imageUrl);
                }
            }

            // --- 5. 所有已知格式都不匹配 ---
            // 打印完整响应方便排查，避免静默失败
            throw new RuntimeException("无法解析API响应格式，完整响应: " + responseBody);

        } catch (RuntimeException e) {
            // 已经是业务异常，直接向上抛出，不再包装
            throw e;
        } catch (Exception e) {
            // 网络异常、JSON 解析异常等未预期错误，统一包装后抛出
            log.error("图片生成失败", e);
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }

    /** 从 URL 下载图片到内存字节数组。 */
    private byte[] downloadFromUrl(String imageUrl) {
        try {
            log.info("开始下载图片: url={}", imageUrl);

            java.net.URI uri = new java.net.URI(imageUrl);
            ResponseEntity<byte[]> imageResponse = restTemplate.exchange(
                    uri, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
            byte[] imageBytes = imageResponse.getBody();

            if (imageBytes == null || imageBytes.length == 0) {
                throw new RuntimeException("下载的图片为空");
            }

            log.info("图片下载完成: size={} bytes", imageBytes.length);
            return imageBytes;
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("URL 格式错误: " + imageUrl, e);
        }
    }
}
