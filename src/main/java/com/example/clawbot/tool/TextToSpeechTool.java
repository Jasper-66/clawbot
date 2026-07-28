package com.example.clawbot.tool;

import com.example.clawbot.service.SpeechService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文字转语音（TTS）工具 — LLM 可调用的语音合成能力。
 *
 * <p>作为 LLM Function Calling 的工具之一，注册为 {@code text_to_speech}。
 * 当用户希望"听到"某段文字时，LLM 调用此工具将文字合成为 WAV 音频文件。</p>
 *
 * <h3>设计考量：为什么保存为文件而非嵌入 Base64？</h3>
 * <p>音频文件动辄几十 KB，如果以 Base64 字符串嵌入 LLM 的 messages 中，
 * 会迅速耗尽 token 配额和上下文窗口。此工具改为将音频保存到本地文件，
 * 仅返回文件路径和元信息的 JSON，供 WeChatBotService 后续读取并发送。</p>
 *
 * <h3>语音标记机制</h3>
 * <p>工具执行后在 LLM 的回复中嵌入 {@code [audio:/absolute/path/to/file.wav]} 标记。
 * {@link com.example.clawbot.service.WeChatBotService#handleLlmReply} 在发送前
 * 检测此标记，读取文件并通过微信发送语音消息。</p>
 *
 * <h3>文件存储</h3>
 * <p>音频文件保存在项目根目录的 {@code audio_cache/} 子目录下，
 * 使用时间戳 + UUID 命名（如 {@code tts_1712345678000_a1b2c3d4.wav}）。</p>
 *
 * @see com.example.clawbot.service.SpeechService
 * @see com.example.clawbot.service.WeChatBotService#handleLlmReply
 * @see com.example.clawbot.service.LlmService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextToSpeechTool {

    /** TTS 核心服务，负责调用 DashScope API 生成音频 */
    private final SpeechService speechService;

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具名称 */
    private static final String NAME = "text_to_speech";

    /** 单次合成最大文本长度，防止恶意超长文本 */
    private static final int MAX_TEXT_LENGTH = 1000;

    /**
     * 工具描述。
     *
     * <p>告知 LLM 此工具的功能和使用场景：用户想要听到朗读/语音消息时调用。
     * 同时提示 LLM 在回复中使用 [audio:path] 标记。</p>
     */
    private static final String DESCRIPTION = "将文字合成为 WAV 格式的语音。当用户希望听到某段文字的朗读、生成语音消息或音频内容时，使用此工具。生成的音频会保存到本地文件并返回路径。";

    /** Function Calling 场景下的默认用户 ID（无微信会话上下文时使用） */
    private static final String DEFAULT_USER_ID = "function_calling_user";

    /** 音频缓存目录名 */
    private static final String AUDIO_DIR = "audio_cache";

    /**
     * 获取工具名称。
     *
     * @return 工具标识名 "text_to_speech"
     */
    public String getToolName() {
        return NAME;
    }

    @Tool(name = "text_to_speech", description = "将文字合成为 WAV 格式的语音。当用户希望听到某段文字的朗读、生成语音消息时使用此工具。")
    public String synthesizeSpeech(
            @ToolParam(required = true, description = "要转换为语音的文字内容") String text) {
        if (text == null || text.trim().isEmpty()) {
            return "工具调用失败：text 参数不能为空";
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return "工具调用失败：text 参数过长（最大 " + MAX_TEXT_LENGTH + " 字符）";
        }
        try {
            log.info("执行语音合成工具: textLength={}", text.length());
            byte[] wavBytes = speechService.textToSpeech(DEFAULT_USER_ID, text.trim());
            if (wavBytes == null || wavBytes.length == 0) {
                return "工具调用失败：语音合成返回为空";
            }
            Path audioDir = Paths.get(AUDIO_DIR);
            Files.createDirectories(audioDir);
            String fileName = "tts_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ".wav";
            Path filePath = audioDir.resolve(fileName);
            Files.write(filePath, wavBytes);
            log.info("语音文件已保存: path={}, size={} bytes", filePath.toAbsolutePath(), wavBytes.length);
            return objectMapper.writeValueAsString(Map.of(
                    "format", "wav", "size", wavBytes.length,
                    "file_path", filePath.toAbsolutePath().toString(),
                    "message", "语音已生成并保存到本地文件"
            ));
        } catch (Exception e) {
            log.error("语音合成工具执行失败: {}", e.getMessage());
            return "工具调用失败：语音合成异常: " + e.getMessage();
        }
    }

    /**
     * 获取工具定义（OpenAI Function Calling 格式）。
     *
     * <h3>参数说明</h3>
     * <ul>
     *   <li>{@code text}（必填）— 要转换为语音的文字内容，建议中文或英文</li>
     * </ul>
     *
     * @return Function Calling 格式的工具定义 Map
     */
    public Map<String, Object> getToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", NAME,
                        "description", DESCRIPTION,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "text", Map.of(
                                                "type", "string",
                                                "description", "要转换为语音的文字内容，建议使用中文或英文"
                                        )
                                ),
                                "required", List.of("text")
                        )
                )
        );
    }

    /**
     * 校验并执行模型返回的工具调用。
     *
     * <p>流程：参数校验 → 调用 TTS 生成音频 → 保存为本地 WAV 文件 → 返回路径 JSON。</p>
     *
     * <h3>返回 JSON 格式</h3>
     * <pre>{@code
     * {
     *   "format": "wav",
     *   "size": 12345,
     *   "file_path": "/absolute/path/to/audio_cache/tts_xxx.wav",
     *   "message": "语音已生成并保存到本地文件，可用于发送给用户"
     * }
     * }</pre>
     *
     * @param functionName  工具名称（应为 "text_to_speech"）
     * @param argumentsJson LLM 生成的参数 JSON（如 {@code {"text":"你好世界"}}）
     * @return 语音文件信息 JSON 字符串，或错误信息
     */
    public String execute(String functionName, String argumentsJson) {
        if (!NAME.equals(functionName)) {
            return "工具调用失败：不支持的工具 " + functionName;
        }

        try {
            // 解析并校验参数
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String text = arguments.path("text").asText("").trim();
            if (text.isEmpty()) {
                return "工具调用失败：text 参数不能为空";
            }
            if (text.length() > MAX_TEXT_LENGTH) {
                return "工具调用失败：text 参数过长（最大 " + MAX_TEXT_LENGTH + " 字符）";
            }

            log.info("执行语音合成工具: textLength={}", text.length());
            byte[] wavBytes = speechService.textToSpeech(DEFAULT_USER_ID, text);

            if (wavBytes == null || wavBytes.length == 0) {
                return "工具调用失败：语音合成返回为空";
            }

            // 保存到本地文件（避免把 Base64 塞进 messages 导致上下文超限）
            Path audioDir = Paths.get(AUDIO_DIR);
            Files.createDirectories(audioDir);
            String fileName = "tts_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ".wav";
            Path filePath = audioDir.resolve(fileName);
            Files.write(filePath, wavBytes);

            log.info("语音文件已保存: path={}, size={} bytes", filePath.toAbsolutePath(), wavBytes.length);

            return objectMapper.writeValueAsString(Map.of(
                    "format", "wav",
                    "size", wavBytes.length,
                    "file_path", filePath.toAbsolutePath().toString(),
                    "message", "语音已生成并保存到本地文件，可用于发送给用户"
            ));
        } catch (Exception e) {
            log.error("语音合成工具执行失败: {}", e.getMessage());
            return "工具调用失败：语音合成异常: " + e.getMessage();
        }
    }
}
