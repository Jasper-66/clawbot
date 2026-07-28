package com.example.clawbot.tool;

import com.example.clawbot.service.ImageGenerationService;
import com.example.clawbot.service.WeChatBotService;
import com.example.clawbot.service.WeChatSessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 图片生成工具 — LLM 通过 Function Calling 调用。
 */
@Slf4j
@Component
public class ImageGenerationTool {

    private final ImageGenerationService imageGenerationService;
    private final WeChatBotService weChatBotService;
    private final WeChatSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageGenerationTool(ImageGenerationService imageGenerationService,
                               @Lazy WeChatBotService weChatBotService,
                               @Lazy WeChatSessionManager sessionManager) {
        this.imageGenerationService = imageGenerationService;
        this.weChatBotService = weChatBotService;
        this.sessionManager = sessionManager;
    }

    private static final String NAME = "generate_image";

    public String getToolName() { return NAME; }

    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", "根据文字描述生成图片并发送给用户。当用户要求「画一个」「生成图片」「生成一张」等意图时使用。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "prompt", Map.of(
                                                "type", "string",
                                                "description", "图片描述，用英文效果更好。如：a cute cat, a beautiful sunset over mountains"
                                        )
                                ),
                                "required", List.of("prompt")
                        )
                )
        );
    }

    @Tool(name = "generate_image", description = "根据文字描述生成图片并发送给用户。当用户要求「画一个」「生成图片」「生成一张」等意图时使用。")
    public String generateImage(
            @ToolParam(required = true, description = "图片描述，如：a cute cat, a beautiful sunset") String prompt) {

        String userId = RemindContextHolder.getUserId();
        if (userId == null) return "⚠️ 无法获取用户信息";

        try {
            log.info("图片生成请求: prompt={}", prompt);
            byte[] imageBytes = imageGenerationService.generateImage(prompt);

            if (imageBytes == null || imageBytes.length == 0) {
                return "⚠️ 图片生成失败：返回为空";
            }

            ILinkClient client = sessionManager.getActiveClient();
            if (client == null) return "⚠️ 无活跃会话，无法发送图片";
            weChatBotService.sendReminderImage(client, userId, imageBytes, prompt);
            return "✅ 图片已生成并发送给用户";

        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage(), e);
            return "⚠️ 图片生成失败：" + e.getMessage();
        }
    }

    public String execute(String functionName, String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            return generateImage(args.path("prompt").asText(""));
        } catch (Exception e) {
            return "⚠️ 参数解析失败：" + e.getMessage();
        }
    }
}
