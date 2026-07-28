package com.example.clawbot.tool;

import com.example.clawbot.service.SpeechService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TextToSpeechTool {

    private final SpeechService speechService;

    public static final String TOOL_NAME = "text_to_speech";
    private static final int MAX_TEXT_LENGTH = 1000;
    private static final String DEFAULT_USER_ID = "function_calling_user";
    private static final String AUDIO_DIR = "audio_cache";

    @Tool(name = "text_to_speech", description = "将文字合成为WAV格式的语音。当用户希望听到某段文字的朗读、生成语音消息或音频内容时使用此工具")
    public String textToSpeech(@ToolParam(description = "要转换为语音的文字内容，建议使用中文或英文") String text) {
        if (text == null || text.isBlank()) {
            return "{\"error\":\"text 参数不能为空\"}";
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return "{\"error\":\"text 参数过长（最大 " + MAX_TEXT_LENGTH + " 字符）\"}";
        }

        try {
            log.info("执行语音合成工具: textLength={}", text.length());
            byte[] wavBytes = speechService.textToSpeech(DEFAULT_USER_ID, text);

            if (wavBytes == null || wavBytes.length == 0) {
                return "{\"error\":\"语音合成返回为空\"}";
            }

            Path audioDir = Paths.get(AUDIO_DIR);
            Files.createDirectories(audioDir);
            String fileName = "tts_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ".wav";
            Path filePath = audioDir.resolve(fileName);
            Files.write(filePath, wavBytes);

            log.info("语音文件已保存: path={}, size={} bytes", filePath.toAbsolutePath(), wavBytes.length);

            return "{\"format\":\"wav\",\"size\":" + wavBytes.length
                    + ",\"file_path\":\"" + filePath.toAbsolutePath().toString().replace("\\", "\\\\")
                    + "\",\"message\":\"语音已生成并保存到本地文件\"}";
        } catch (Exception e) {
            log.error("语音合成工具执行失败: {}", e.getMessage());
            return "{\"error\":\"语音合成异常: " + e.getMessage() + "\"}";
        }
    }
}
