package com.example.clawbot.tool;

import com.example.clawbot.service.SpeechService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** LLM Function Calling 工具，调用 SpeechService 将文字合成为 WAV 音频文件。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextToSpeechTool {

    private final SpeechService speechService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NAME = "text_to_speech";
    private static final int MAX_TEXT_LENGTH = 1000;
    private static final String DESCRIPTION = "将文字合成为 WAV 格式的语音。当用户希望听到某段文字的朗读、生成语音消息或音频内容时，使用此工具。生成的音频会保存到本地文件并返回路径。";
    private static final String DEFAULT_USER_ID = "function_calling_user";
    private static final String AUDIO_DIR = "audio_cache";

    public String getToolName() {
        return NAME;
    }

    /** 返回 OpenAI Function Calling 格式的工具定义。 */
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

    /** 校验参数 → 调用 TTS → 保存为本地 WAV 文件 → 返回路径 JSON。 */
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
