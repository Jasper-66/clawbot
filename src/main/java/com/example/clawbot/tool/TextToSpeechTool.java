package com.example.clawbot.tool;

import com.example.clawbot.service.SpeechService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

// 文字转语音工具：LLM 可调用将文本合成为 WAV 语音文件（DashScope TTS）
@Slf4j
@Component
@RequiredArgsConstructor
public class TextToSpeechTool {

    private final SpeechService speechService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_TEXT_LENGTH = 1000;
    private static final String DEFAULT_USER_ID = "function_calling_user";
    private static final String AUDIO_DIR = "audio_cache";

    @Tool(name = "text_to_speech", description = "将文字合成为 WAV 格式的语音。当用户希望听到某段文字的朗读、生成语音消息或音频内容时，使用此工具。生成的音频会保存到本地文件并返回路径。")
    public String textToSpeech(
            @ToolParam(description = "要转换为语音的文字内容，建议使用中文或英文") String text,
            @ToolParam(required = false, description = "用户唯一标识，用于读取该用户选择的音色") String user_id) {

        if (text == null || text.trim().isEmpty()) {
            return "工具调用失败：text 参数不能为空";
        }
        String trimmedText = text.trim();
        if (trimmedText.length() > MAX_TEXT_LENGTH) {
            return "工具调用失败：text 参数过长（最大 " + MAX_TEXT_LENGTH + " 字符）";
        }

        String effectiveUserId = (user_id == null || user_id.trim().isEmpty())
                ? DEFAULT_USER_ID
                : user_id.trim();

        log.info("[行动] LLM调用工具: text_to_speech(textLength={}) → 调用DashScope TTS将文本转为WAV语音", trimmedText.length());

        try {
            byte[] wavBytes = speechService.textToSpeech(effectiveUserId, trimmedText);

            if (wavBytes == null || wavBytes.length == 0) {
                return "工具调用失败：语音合成返回为空";
            }

            Path audioDir = Paths.get(AUDIO_DIR);
            Files.createDirectories(audioDir);
            String fileName = "tts_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ".wav";
            Path filePath = audioDir.resolve(fileName);
            Files.write(filePath, wavBytes);

            log.info("[观察] 工具返回: text_to_speech → WAV文件已生成: path={}, size={} bytes",
                    filePath.toAbsolutePath(), wavBytes.length);

            return objectMapper.writeValueAsString(Map.of(
                    "format", "wav",
                    "size", wavBytes.length,
                    "file_path", filePath.toAbsolutePath().toString(),
                    "message", "语音已生成并保存到本地文件，可用于发送给用户"
            ));
        } catch (Exception e) {
            log.error("[异常] 工具调用失败 text_to_speech | 原因: {} | 建议: 检查 DashScope TTS API 密钥",
                    effectiveUserId, trimmedText.length(), e.getMessage(), e);
            return "工具调用失败：语音合成异常: " + e.getMessage();
        }
    }
}
