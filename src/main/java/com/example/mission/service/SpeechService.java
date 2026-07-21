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
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpeechService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${vision.api.key}")
    private String apiKey;

    @Value("${vision.api.base-url}")
    private String compatibleBaseUrl;

    @Value("${speech.tts.model:qwen3-tts-flash}")
    private String ttsModel;

    @Value("${speech.tts.voice:Cherry}")
    private String ttsVoice;

    @Value("${speech.asr.model:qwen3-asr-flash}")
    private String asrModel;

    @Value("${silk.decoder.path}")
    private String silkDecoderPath;

    private static final String DASHSCOPE_BASE = "https://dashscope.aliyuncs.com";
    private static final String TTS_ENDPOINT = "/api/v1/services/aigc/multimodal-generation/generation";

    /**
     * 文字转语音，返回音频字节数组。
     */
    public byte[] textToSpeech(String text) {
        try {
            Map<String, Object> input = Map.of(
                    "text", text,
                    "voice", ttsVoice,
                    "language_type", "Chinese"
            );
            Map<String, Object> requestBody = Map.of(
                    "model", ttsModel,
                    "input", input
            );

            HttpHeaders headers = new HttpHeaders();
            //告诉服务器发送json格式
            headers.setContentType(MediaType.APPLICATION_JSON);
            //鉴权
            headers.setBearerAuth(apiKey);

            String requestJson = objectMapper.writeValueAsString(requestBody); //将java对象转变成字符串
            log.info("TTS 请求: model={}, text={}", ttsModel, text);

            //请求头：类比快递东西
            //请求体: 快递标签
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    DASHSCOPE_BASE + TTS_ENDPOINT, entity, String.class);// 1.收件地址 2.包裹 3.期望返回类型


            //服务器返回后，Spring 把返回结果包装成一个 ResponseEntity 对象，你可以拿到：

          //  response.getStatusCode();     // HTTP 状态码，比如 200 OK
           // response.getHeaders();        // 服务器返回的响应头
            // response.getBody();           // 响应体（一个 JSON 字符串）


            String responseBody = response.getBody();
            if (responseBody == null) {
                throw new RuntimeException("TTS 返回空响应");
            }

            JsonNode root = objectMapper.readTree(responseBody);

            if (root.has("code")) {
                String code = root.path("code").asText("");
                String msg = root.path("message").asText("未知错误");
                if (!"0".equals(code) && !"".equals(code)) {
                    throw new RuntimeException("TTS 失败 [" + code + "]: " + msg);
                }
            }

            String audioUrl = root.path("output").path("audio").path("url").asText();
            if (audioUrl.isEmpty()) {
                throw new RuntimeException("TTS 响应中未找到音频 URL");
            }

            return downloadFromUrl(audioUrl);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS 失败", e);
            throw new RuntimeException("语音合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 语音转文字。将音频字节 Base64 编码后通过 /chat/completions 端点
     * 调用 qwen3-asr-flash 模型，同步返回识别文本。
     */
    public String speechToText(byte[] audioBytes, String fileName) {
        try {
            // 非 WAV 格式（如微信 SILK/AMR）先转码，否则 ASR 无法识别
            byte[] converted = convertToWavIfNeeded(audioBytes, fileName);
            String base64Audio = Base64.getEncoder().encodeToString(converted);
            String dataUrl = "data:audio/wav;base64," + base64Audio;

            List<Map<String, Object>> content = List.of(
                    Map.of("type", "input_audio",
                            "input_audio", Map.of("data", dataUrl))
            );

            Map<String, Object> requestBody = Map.of(
                    "model", asrModel,
                    "messages", List.of(Map.of("role", "user", "content", content))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.info("ASR 请求: model={}, file={}, base64Length={}", asrModel, fileName, base64Audio.length());

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    compatibleBaseUrl + "/v1/chat/completions", entity, String.class);

            String responseBody = response.getBody();
            if (responseBody == null) {
                throw new RuntimeException("ASR 返回空响应");
            }

            JsonNode root = objectMapper.readTree(responseBody);

            if (root.has("error")) {
                log.error("ASR API 错误: {}", root.get("error"));
                throw new RuntimeException("语音识别失败: " + root.get("error"));
            }

            String text = root.path("choices").get(0).path("message").path("content").asText();
            log.info("ASR 识别结果: {}", text);
            return text.trim();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("ASR 失败", e);
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 非 WAV 格式（微信 SILK/AMR）先用 silk_v3_decoder 解码为 PCM，再加 WAV 头。
     */
    private byte[] convertToWavIfNeeded(byte[] audioBytes, String fileName) {
        String lower = fileName != null ? fileName.toLowerCase() : "";
        if (lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac")) {
            return audioBytes;
        }
        java.io.File silkFile = null;
        java.io.File pcmFile = null;
        try {
            log.info("SILK 解码: {} → PCM, inputSize={}", fileName, audioBytes.length);

            //创建了两个临时文件：一个用于存放待解码的 SILK 数据，另一个用于接收解码后的 PCM 数据。
            silkFile = java.io.File.createTempFile("voice_", ".silk");
            pcmFile = java.io.File.createTempFile("voice_", ".pcm");
            java.nio.file.Files.write(silkFile.toPath(), audioBytes);

            //构建一个操作系统级别的进程
            ProcessBuilder pb = new ProcessBuilder(
                    silkDecoderPath, silkFile.getAbsolutePath(), pcmFile.getAbsolutePath(), "-quiet"
            );
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String err = new String(process.getErrorStream().readAllBytes());
                log.error("SILK 解码失败, exitCode={}, stderr={}", exitCode, err);
                throw new RuntimeException("SILK 解码失败");
            }

            byte[] pcm = java.nio.file.Files.readAllBytes(pcmFile.toPath());
            byte[] wav = addWavHeader(pcm, 24000);
            log.info("SILK 解码完成: pcmSize={}, wavSize={}", pcm.length, wav.length);
            return wav;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("SILK 解码异常", e);
            throw new RuntimeException("SILK 解码失败: " + e.getMessage(), e);
        } finally {
            if (silkFile != null) silkFile.delete();
            if (pcmFile != null) pcmFile.delete();
        }
    }

    /**
     * 给 PCM 裸数据加上 44 字节 WAV 头，使其成为标准 WAV 文件。
     */
    private byte[] addWavHeader(byte[] pcm, int sampleRate) {
        int dataSize = pcm.length;
        byte[] wav = new byte[44 + dataSize];
        // RIFF
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        intLE(wav, 4, 36 + dataSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        // fmt
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        intLE(wav, 16, 16);
        shortLE(wav, 20, (short) 1); // PCM
        shortLE(wav, 22, (short) 1); // mono
        intLE(wav, 24, sampleRate);
        intLE(wav, 28, sampleRate * 2);
        shortLE(wav, 32, (short) 2);
        shortLE(wav, 34, (short) 16);
        // data
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        intLE(wav, 40, dataSize);
        System.arraycopy(pcm, 0, wav, 44, dataSize);
        return wav;
    }

    private static void intLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
        buf[offset + 2] = (byte) (value >> 16);
        buf[offset + 3] = (byte) (value >> 24);
    }

    private static void shortLE(byte[] buf, int offset, short value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
    }

    /**
     * 从 URL 下载文件到字节数组。
     * 使用 URI 对象直接请求，避免 RestTemplate 对预签名 URL 中的特殊字符做二次编码。
     */
    private byte[] downloadFromUrl(String url) {
        try {
            log.info("开始下载: url={}", url);
            java.net.URI uri = new java.net.URI(url);
            //1.url 2.get请求 3.不需要请求体 4.解析为字节数组
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    uri, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length == 0) {
                throw new RuntimeException("下载文件为空");
            }
            log.info("下载完成: size={} bytes", bytes.length);
            return bytes;
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("URL 格式错误: " + url, e);
        }
    }

    private String getMimeType(String fileName) {
        if (fileName == null) return "audio/wav";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".amr")) return "audio/amr";
        if (lower.endsWith(".sil")) return "audio/silk";
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        return "audio/wav";
    }
}
