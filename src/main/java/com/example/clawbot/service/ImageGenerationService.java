package com.example.mission.service;

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

/**
 * 图片生成服务。
 * <p>
 * 调用智谱 CogView-3-Plus API（OpenAI 兼容的 /images/generations 端点），
 * 根据用户输入的文本描述（prompt）生成图片，返回图片的字节数组供机器人发送。
 * </p>
 *
 * <h3>调用链路</h3>
 * <pre>
 * 用户文本描述 → WeChatBotService 提取 prompt → generateImage(prompt)
 *   → POST /images/generations（智谱 API）
 *   → 解析响应（兼容 OpenAI / DashScope 多种格式）
 *   → 下载图片 / 解码 base64 → 返回 byte[]
 *   → WeChatBotService.sendImage() 发送给用户
 * </pre>
 *
 * <h3>支持的响应格式（兼容多种 API 提供商）</h3>
 * <ul>
 *   <li>OpenAI 成功格式: {@code {"data": [{"url": "https://..."}]}}</li>
 *   <li>OpenAI base64 格式: {@code {"data": [{"b64_json": "..."}]}}</li>
 *   <li>OpenAI 错误格式: {@code {"error": {"message": "..."}}}</li>
 *   <li>DashScope 成功格式: {@code {"output": {"results": [{"url": "https://..."}]}}}</li>
 *   <li>DashScope 错误格式: {@code {"code": "InvalidParameter", "message": "..."}}</li>
 * </ul>
 *
 * @see WeChatBotService#handleImageGeneration(String, String)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    /**
     * Spring 管理的 RestTemplate Bean，由 {@code RestTemplateConfig} 创建。
     * 用于发起所有 HTTP 请求（API 调用 + 图片下载）。
     */
    private final RestTemplate restTemplate;

    /**
     * Jackson JSON 解析器。用于将 API 返回的 JSON 字符串解析为树形结构，
     * 再按路径提取字段值。相比定义实体类更灵活，能适配多种 API 提供商的响应差异。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 图片生成 API 的鉴权密钥。
     * 通过 HTTP Header {@code Authorization: Bearer <apiKey>} 传递。
     * 配置项: {@code image.gen.api.key}
     */
    @Value("${image.gen.api.key}")
    private String apiKey;

    /**
     * 图片生成 API 的基础地址，不含端点路径。
     * 例如智谱: {@code https://open.bigmodel.cn/api/paas/v4}
     * 配置项: {@code image.gen.api.base-url}
     */
    @Value("${image.gen.api.base-url}")
    private String baseUrl;

    /**
     * 图片生成模型名称，会放入请求体的 {@code model} 字段。
     * 例如智谱: {@code cogview-3-plus}
     * 配置项: {@code image.gen.api.model}
     */
    @Value("${image.gen.api.model}")
    private String model;

    /**
     * 图片生成 API 的端点路径，会拼接在 baseUrl 后面。
     * 例如智谱: {@code /images/generations}，OpenAI: {@code /v1/images/generations}
     * 配置项: {@code image.gen.api.endpoint}
     */
    @Value("${image.gen.api.endpoint}")
    private String endpoint;

    /**
     * 根据文本描述生成图片。
     *
     * <h3>请求参数说明</h3>
     * <ul>
     *   <li><b>model</b>: 模型名称（如 cogview-3-plus）</li>
     *   <li><b>prompt</b>: 图片描述文本，中英文均可，越详细效果越好</li>
     *   <li><b>size</b>: 生成图片尺寸，固定 {@code 1024x1024}（1:1 正方形）</li>
     *   <li><b>n</b>: 每次生成数量，固定 1（多数 API 单次只支持 1 张）</li>
     * </ul>
     *
     * @param prompt 图片描述文本，由 {@code WeChatBotService.extractImagePrompt()} 从用户消息中提取
     * @return 生成图片的字节数组（PNG 格式），可直接传给 {@code client.sendImage()}
     * @throws RuntimeException 图片生成或下载失败时抛出，调用方会捕捉并向用户发送错误提示
     */
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

    /**
     * 从 URL 下载图片到内存。
     * <p>
     * 大部分图片生成 API 返回的是临时 URL（有效期通常数小时），
     * 需要尽快下载。下载后以 {@code byte[]} 形式保存在内存中，
     * 用完即被 GC 回收，不会在磁盘留下临时文件。
     * </p>
     *
     * @param imageUrl 图片的临时下载地址
     * @return 图片字节数组
     * @throws RuntimeException 下载失败或返回内容为空时抛出
     */
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
