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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TTS（文字转语音）和 ASR（语音转文字）服务，通过阿里云 DashScope API 实现，支持 15 种音色切换和微信音频格式转码。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeechService {

    private final RestTemplate restTemplate;
    private final ConversationMemoryService memoryService;
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

    /** 预定义音色表，key=英文参数名, value=[中文名, 描述] */
    private static final LinkedHashMap<String, String[]> VOICE_INFO = new LinkedHashMap<>();
    static {
        VOICE_INFO.put("Cherry",    new String[]{"芊悦", "阳光积极、亲切自然小姐姐"});
        VOICE_INFO.put("Ethan",     new String[]{"晨煦", "标准普通话、阳光温暖男声"});
        VOICE_INFO.put("Serena",    new String[]{"苏瑶", "温柔小姐姐"});
        VOICE_INFO.put("Chelsie",   new String[]{"千雪", "二次元虚拟女友"});
        VOICE_INFO.put("Momo",      new String[]{"茉兔", "撒娇搞怪、逗你开心"});
        VOICE_INFO.put("Vivian",    new String[]{"十三", "拽拽的可爱小暴躁"});
        VOICE_INFO.put("Bella",     new String[]{"萌宝", "喝酒不打醉拳的小萝莉"});
        VOICE_INFO.put("Mia",       new String[]{"乖小妹", "温顺如春水、乖巧如初雪"});
        VOICE_INFO.put("Nofish",    new String[]{"不吃鱼", "不会翘舌音的设计师男声"});
        VOICE_INFO.put("Kai",       new String[]{"凯", "耳朵的一场SPA男声"});
        VOICE_INFO.put("Neil",      new String[]{"阿闻", "专业新闻主持人的咬字发音"});
        VOICE_INFO.put("Eldric Sage", new String[]{"沧明子", "沉稳睿智的老者"});
        VOICE_INFO.put("Vincent",   new String[]{"田叔", "沙哑烟嗓、江湖豪情"});
        VOICE_INFO.put("Sunny",     new String[]{"四川-晴儿", "甜到你心里的川妹子"});
        VOICE_INFO.put("Rocky",     new String[]{"粤语-阿强", "幽默风趣的粤语男声"});
    }

    /** 音色标签映射，用于模糊描述匹配 */
    private static final LinkedHashMap<String, java.util.Set<String>> VOICE_TAGS =
            new LinkedHashMap<>();
    static {
        VOICE_TAGS.put("Cherry",    java.util.Set.of("阳光", "积极", "亲切", "自然", "小姐姐"));
        VOICE_TAGS.put("Ethan",     java.util.Set.of("标准", "普通话", "阳光", "温暖"));
        VOICE_TAGS.put("Serena",    java.util.Set.of("温柔", "小姐姐", "甜"));
        VOICE_TAGS.put("Chelsie",   java.util.Set.of("二次元", "虚拟", "女友"));
        VOICE_TAGS.put("Momo",      java.util.Set.of("撒娇", "搞怪", "逗"));
        VOICE_TAGS.put("Vivian",    java.util.Set.of("拽", "暴躁"));
        VOICE_TAGS.put("Bella",     java.util.Set.of("萝莉", "萌"));
        VOICE_TAGS.put("Mia",       java.util.Set.of("温顺", "乖巧", "温柔"));
        VOICE_TAGS.put("Nofish",    java.util.Set.of("设计师"));
        VOICE_TAGS.put("Kai",       java.util.Set.of("SPA", "耳朵", "温柔"));
        VOICE_TAGS.put("Neil",      java.util.Set.of("新闻", "专业", "主持人", "咬字"));
        VOICE_TAGS.put("Eldric Sage", java.util.Set.of("沉稳", "睿智", "老者", "成熟"));
        VOICE_TAGS.put("Vincent",   java.util.Set.of("沙哑", "烟嗓", "江湖", "豪情", "大叔"));
        VOICE_TAGS.put("Sunny",     java.util.Set.of("四川", "川妹子", "甜"));
        VOICE_TAGS.put("Rocky",     java.util.Set.of("粤语", "广东话", "幽默", "风趣"));
    }

    /** 男性音色集合 */
    private static final java.util.Set<String> MALE_VOICES =
            java.util.Set.of("Ethan", "Nofish", "Kai", "Neil", "Eldric Sage", "Vincent", "Rocky");

    /** 女性音色集合 */
    private static final java.util.Set<String> FEMALE_VOICES =
            java.util.Set.of("Cherry", "Serena", "Chelsie", "Momo", "Vivian", "Bella", "Mia", "Sunny");

    /** 性别关键词 — 用于模糊匹配时自动附加到对应音色 */
    private static final java.util.Set<String> MALE_KEYWORDS =
            java.util.Set.of("男声", "男生", "男性", "男", "男孩", "小哥哥", "哥哥");
    private static final java.util.Set<String> FEMALE_KEYWORDS =
            java.util.Set.of("女声", "女生", "女性", "女", "女孩", "小姐姐", "妹妹");

    /** SILK 解码器路径，用于将微信 SILK 格式转为 PCM */
    @Value("${silk.decoder.path}")
    private String silkDecoderPath;

    private static final String DASHSCOPE_BASE = "https://dashscope.aliyuncs.com";
    private static final String TTS_ENDPOINT = "/api/v1/services/aigc/multimodal-generation/generation";

    /** TTS 文字转语音，调用 DashScope 多模态生成 API 合成 WAV 音频。 */
    public byte[] textToSpeech(String userId, String text) {
        try {
            // 确定使用的音色（优先从 Redis 长期记忆中读取用户偏好）
            String voicePref = memoryService.getVoicePreference(userId);
            String voice = voicePref != null ? voicePref : ttsVoice;

            // 构建 TTS 请求体
            Map<String, Object> input = Map.of(
                    "text", text,
                    "voice", voice,
                    "language_type", "Chinese"
            );
            Map<String, Object> requestBody = Map.of(
                    "model", ttsModel,
                    "input", input
            );

            // 设置请求头：JSON 内容类型 + Bearer Token 认证
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.info("TTS 请求: model={}, text={}", ttsModel, text);

            // POST 到 DashScope TTS API
            // HttpEntity: 请求头（信封）+ 请求体（内容）
            // postForEntity: 1.目标URL 2.请求实体 3.期望返回类型
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    DASHSCOPE_BASE + TTS_ENDPOINT, entity, String.class);

            // ResponseEntity 包含三个部分：
            // response.getStatusCode() — HTTP 状态码（200 OK）
            // response.getHeaders()    — 服务器返回的响应头
            // response.getBody()      — 响应体（JSON 字符串）

            String responseBody = response.getBody();
            if (responseBody == null) {
                throw new RuntimeException("TTS 返回空响应");
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // DashScope 错误通过 code 字段返回（非 0 即错误）
            if (root.has("code")) {
                String code = root.path("code").asText("");
                String msg = root.path("message").asText("未知错误");
                if (!"0".equals(code) && !"".equals(code)) {
                    throw new RuntimeException("TTS 失败 [" + code + "]: " + msg);
                }
            }

            // 提取音频下载 URL（预签名 URL，可直接下载）
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

    /** ASR 语音转文字，将音频 Base64 编码后通过 DashScope ASR 模型识别为文本。 */
    public String speechToText(byte[] audioBytes, String fileName) {
        try {
            // 非标准格式先转为 WAV（微信常用 SILK/AMR），否则 ASR 无法识别
            byte[] converted = convertToWavIfNeeded(audioBytes, fileName);
            String base64Audio = Base64.getEncoder().encodeToString(converted);
            String dataUrl = "data:audio/wav;base64," + base64Audio;

            // 构建 ASR 请求：使用 input_audio 内容类型
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

            // 检查 API 错误（如 API key 无效）
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

    /** 将非标准音频格式（SILK/AMR）通过 silk_v3_decoder 解码并添加 WAV 文件头转为标准 WAV。 */
    private byte[] convertToWavIfNeeded(byte[] audioBytes, String fileName) {
        String lower = fileName != null ? fileName.toLowerCase() : "";

        // 已知 ASR 支持的格式直接返回
        if (lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac")) {
            return audioBytes;
        }

        java.io.File silkFile = null;
        java.io.File pcmFile = null;
        try {
            log.info("SILK 解码: {} → PCM, inputSize={}", fileName, audioBytes.length);

            // 创建临时文件：一个存 SILK 数据，一个接收 PCM 输出
            silkFile = java.io.File.createTempFile("voice_", ".silk");
            pcmFile = java.io.File.createTempFile("voice_", ".pcm");
            java.nio.file.Files.write(silkFile.toPath(), audioBytes);

            // 启动外部解码进程：silk_v3_decoder <输入> <输出> -quiet
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

            // 读取 PCM 并添加 WAV 文件头
            byte[] pcm = java.nio.file.Files.readAllBytes(pcmFile.toPath());
            byte[] wav = addWavHeader(pcm, 24000); // 微信语音采样率 24000Hz
            log.info("SILK 解码完成: pcmSize={}, wavSize={}", pcm.length, wav.length);
            return wav;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("SILK 解码异常", e);
            throw new RuntimeException("SILK 解码失败: " + e.getMessage(), e);
        } finally {
            // 清理临时文件
            if (silkFile != null) silkFile.delete();
            if (pcmFile != null) pcmFile.delete();
        }
    }

    /** 为 PCM 裸数据添加 44 字节标准 RIFF/WAVE 文件头（单声道、16bit、小端序）。 */
    private byte[] addWavHeader(byte[] pcm, int sampleRate) {
        int dataSize = pcm.length;
        byte[] wav = new byte[44 + dataSize];

        // ── RIFF chunk ──
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        intLE(wav, 4, 36 + dataSize);  // 文件总长度 - 8
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // ── fmt sub-chunk ──
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        intLE(wav, 16, 16);        // fmt 块大小 (PCM = 16)
        shortLE(wav, 20, (short) 1);    // 音频格式 (1 = PCM)
        shortLE(wav, 22, (short) 1);    // 声道数 (1 = Mono)
        intLE(wav, 24, sampleRate);     // 采样率
        intLE(wav, 28, sampleRate * 2); // 字节率 = 采样率 × 声道数 × 位深/8
        shortLE(wav, 32, (short) 2);    // 块对齐 = 声道数 × 位深/8
        shortLE(wav, 34, (short) 16);   // 位深 (16 bit per sample)

        // ── data sub-chunk ──
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        intLE(wav, 40, dataSize);       // PCM 数据长度

        // ── 拷贝 PCM 数据 ──
        System.arraycopy(pcm, 0, wav, 44, dataSize);
        return wav;
    }

    /** 小端序写入 32 位整数到字节数组。 */
    private static void intLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
        buf[offset + 2] = (byte) (value >> 16);
        buf[offset + 3] = (byte) (value >> 24);
    }

    /** 小端序写入 16 位短整数到字节数组。 */
    private static void shortLE(byte[] buf, int offset, short value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >> 8);
    }

    /** 通过 HTTP GET 下载文件到字节数组，使用 URI 对象避免预签名 URL 的二次编码问题。 */
    private byte[] downloadFromUrl(String url) {
        try {
            log.info("开始下载: url={}", url);
            java.net.URI uri = new java.net.URI(url);
            // exchange 方法的参数：1.目标URI 2.HTTP方法 3.请求实体(无) 4.期望返回类型
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

    /** 设置用户 TTS 音色偏好，支持中英文名模糊匹配。 */
    public String setVoice(String userId, String voiceName) {
        if (voiceName == null || voiceName.isBlank()) {
            return "请描述你想要的音色，例如「切换音色Cherry」、「换个温柔的女生声音」或「有没有沉稳的男声」。";
        }
        String voice = findVoice(voiceName.trim());
        if (voice == null) {
            return "未能匹配到适合「" + voiceName.trim() + "」的音色，发送「音色列表」查看所有音色及描述。";
        }
        memoryService.setVoicePreference(userId, voice);
        String[] info = VOICE_INFO.get(voice);
        return "已切换音色为 " + voice + "（" + info[0] + "·" + info[1] + "）";
    }

    /** 获取用户当前 TTS 音色显示名称。 */
    public String getCurrentVoice(String userId) {
        String voicePref = memoryService.getVoicePreference(userId);
        String voice = voicePref != null ? voicePref : ttsVoice;
        String[] info = VOICE_INFO.get(voice);
        if (info != null) {
            return voice + "（" + info[0] + "·" + info[1] + "）";
        }
        return voice + "（系统默认）";
    }

    /** 获取所有可用音色的格式化列表文本。 */
    public String getAvailableVoices() {
        StringBuilder sb = new StringBuilder("可用音色列表：\n");
        int i = 1;
        for (Map.Entry<String, String[]> entry : VOICE_INFO.entrySet()) {
            sb.append(i++).append(". ").append(entry.getKey())
                    .append("（").append(entry.getValue()[0]).append("）")
                    .append(" — ").append(entry.getValue()[1]).append("\n");
        }
        sb.append("\n发送「切换音色 + 名称」或直接描述想要的音色即可切换，例如「换个温柔的女生声音」。");
        return sb.toString();
    }

    /** 根据用户输入模糊匹配音色参数名，依次尝试精确匹配→包含匹配→标签打分。 */
    private String findVoice(String input) {
        // 第一轮：精确匹配（忽略大小写的英文名、精确中文名）
        for (Map.Entry<String, String[]> entry : VOICE_INFO.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(input)) return entry.getKey();
            if (entry.getValue()[0].equals(input)) return entry.getKey();
        }
        // 第二轮：模糊匹配（包含关系）
        for (Map.Entry<String, String[]> entry : VOICE_INFO.entrySet()) {
            if (entry.getKey().toLowerCase().contains(input.toLowerCase())) return entry.getKey();
            if (entry.getValue()[0].contains(input)) return entry.getKey();
        }
        // 第三轮：标签关键词打分（支持描述性输入如「温柔的女生声音」）
        String lowerInput = input.toLowerCase();
        String bestVoice = null;
        int bestScore = 0;
        for (Map.Entry<String, java.util.Set<String>> tagEntry : VOICE_TAGS.entrySet()) {
            String voiceName = tagEntry.getKey();
            int score = 0;
            for (String tag : tagEntry.getValue()) {
                if (lowerInput.contains(tag)) score++;
            }
            // 性别标签加分
            boolean isMale = MALE_VOICES.contains(voiceName);
            boolean isFemale = FEMALE_VOICES.contains(voiceName);
            for (String kw : MALE_KEYWORDS) {
                if (lowerInput.contains(kw) && isMale) score++;
            }
            for (String kw : FEMALE_KEYWORDS) {
                if (lowerInput.contains(kw) && isFemale) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestVoice = voiceName;
            }
        }
        return bestScore > 0 ? bestVoice : null;
    }

    /** 根据文件扩展名推断音频 MIME 类型。 */
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
