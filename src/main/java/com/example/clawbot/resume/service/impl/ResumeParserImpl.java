package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.service.ResumeParser;
import com.example.clawbot.service.LlmService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParserImpl implements ResumeParser {

    @Lazy
    @Autowired
    private LlmService llmService;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${vision.api.key}")
    private String visionApiKey;

    @Value("${vision.api.base-url}")
    private String visionBaseUrl;

    @Value("${vision.api.model}")
    private String visionModel;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RESUME_TEXT_LENGTH = 6000;

    private static final String MESSAGE_PARSE_PROMPT = """
            你是一个专业的简历信息提取助手。请从用户的自然语言求职描述中提取结构化信息。
            
            ╔══════════════════════════════════════════════════════════════╗
            ║  【最高原则】真实性第一！绝对不能编造信息！                        ║
            ║  • 用户明确提到的信息才能提取，没提到的一律留空                    ║
            ║  • 不要猜测、不要补充、不要"合理推断"，哪怕用户只说了一句话        ║
            ║  • 例如：用户只说"我想找工作"，除了desired_position其他全部留空  ║
            ╚══════════════════════════════════════════════════════════════╝
            
            【字段分层说明】（参考通用简历格式）
            ┌──────────────────────────────────────────────────┐
            │  可选项（用户没说就留空，不要填默认值）：          │
            │  name, phone, email, salary_range, education,    │
            │  skills, summary                                   │
            │                                                    │
            │  注意：desired_city 和 experience_years 由代码层  │
            │  负责补默认值，你只需要提取用户明确说的内容         │
            └──────────────────────────────────────────────────┘
            
            【字段定义与同义词说明】
            1. desired_position（期望职位）：用户想找的工作岗位名称
               - 常见说法：职位、岗位、工作、想做什么、应聘、求职意向、目标职位
               - 示例："Java后端开发"、"前端工程师"、"产品经理"
            
            2. desired_city（期望城市）：用户想在哪个城市工作
               - 常见说法：城市、地点、地方、去哪、想去、工作城市、意向城市
               - 示例："北京"、"上海"、"深圳"
            
            3. salary_range（期望薪资）：用户期望的月薪范围
               - 常见说法：薪资、工资、薪水、待遇、多少钱、收入、期望薪酬
               - 格式统一为"Xk-Yk"，如"15k-25k"、"20k-30k"
               - 如果只说了一个数字，填"Xk-Xk"，如用户说"2万"，填"20k-20k"
            
            4. experience_years（工作年限）：用户全职工作的总年数（整数）
               - 常见说法：工作经验、工龄、做了几年、干了多久、从业年限、工作几年
               - ⚠️ 注意区分：
                 • "工作经验"= 工作总年数 → 填到 experience_years
                 • "项目经验"= 具体做过的项目描述 → 不要填到这里（放到 summary 里）
               - 示例：用户说"3年经验" → experience_years=3
            
            5. education（最高学历）：用户的最高学历
               - 常见说法：学历、文凭、毕业、什么学校、专业背景、文化程度
               - 标准值："小学"、"初中"、"高中"、"中专"、"大专"、"本科"、"硕士"、"博士"
               - 用户说"研究生"统一填"硕士"
            
            6. skills（技能列表）：用户掌握的技术/能力/工具
               - 常见说法：技能、技术、会什么、掌握、擅长、熟悉、能做、工具、栈
               - 每个技能是一个短标签，不要长句
               - 示例：["Java", "Spring Boot", "MySQL", "Redis"]
            
            7. summary（个人简介）：用户对自己的整体描述、自我介绍
               - 常见说法：自我介绍、简介、个人描述、自我评价、我这个人、关于我
               - 如果用户没有专门说，就留空字符串""（不要自己概括）
            
            【歧义处理规则】
            • 用户说"经验"时，优先判断为"工作经验（experience_years）"，除非上下文明确在说具体项目
            • 用户说"做过XX项目"时，把这个信息放到 summary 里（用一句话概括）
            • 分不清是技能还是职位时，职位放到 desired_position，技能放到 skills
            • 时间模糊表述："几年"按整数取，"不到1年"=0，"1-3年"=2（取中间值）
            • ⚠️ 任何时候拿不准，就留空，不要猜！
            
            【输出要求】
            1. 只返回 JSON，不要有任何额外文字或 Markdown 标记
            2. 所有字段都必须出现，但只有用户明确提到的才填值，没提到的：
               - 字符串字段填 ""（空字符串）
               - 数字字段填 0（但代码层会补默认值，你先按实际提取）
               - 数组字段填 []（空数组）
            3. salary_range 没提到就填""，绝对不要填"0k-0k"
            
            【示例1：用户信息齐全】
            用户输入："我想找Java开发，在北京，3年经验，本科，会Spring Boot和MySQL，期望20k左右"
            正确输出：
            {
              "desired_position": "Java开发",
              "desired_city": "北京",
              "salary_range": "20k-20k",
              "experience_years": 3,
              "education": "本科",
              "skills": ["Spring Boot", "MySQL"],
              "summary": ""
            }
            
            【示例2：用户只说了一点点】
            用户输入："我想找前端工作"
            正确输出（除了desired_position，其他全空）：
            {
              "desired_position": "前端",
              "desired_city": "",
              "salary_range": "",
              "experience_years": 0,
              "education": "",
              "skills": [],
              "summary": ""
            }
            
            【用户描述】
            %s
            """;

    private static final String FILE_PARSE_PROMPT = """
            你是一个专业的简历信息提取助手。请从以下简历文本中提取结构化信息。
            
            ╔══════════════════════════════════════════════════════════════╗
            ║  【最高原则】真实性第一！绝对不能编造信息！                        ║
            ║  • 简历原文中明确出现的信息才能提取，没有出现的一律留空            ║
            ║  • 不要猜测、不要补充、不要"合理推断"，哪怕简历写得很简单          ║
            ║  • 招聘平台可能进行背调，虚构信息会影响求职，请务必如实提取          ║
            ╚══════════════════════════════════════════════════════════════╝
            
            【字段分层说明】（参考通用简历格式）
            ┌────────────────────────────────────────────────────────────┐
            │  通用简历模块：                                                │
            │  1. 基本信息（姓名、电话、邮箱）                               │
            │  2. 求职意向（期望职位、期望城市、期望薪资）                     │
            │  3. 教育背景（学校、专业、学历）                                │
            │  4. 实习/工作经历（倒序，STAR法则+数据量化）                    │
            │  5. 项目经历（倒序，角色+技术+成果）                           │
            │  6. 其他（技能、证书、获奖 - 可选项）                          │
            │                                                              │
            │  可选项（简历没写就留空）：                                     │
            │  name, phone, email, salary_range, education, skills,        │
            │  summary, work_history, project_history                        │
            │                                                              │
            │  注意：desired_city 和 experience_years 由代码层负责补默认值，  │
            │  你只需要提取简历原文中明确写了的内容                             │
            └────────────────────────────────────────────────────────────┘
            
            【字段定义与同义词说明】
            1. name（姓名）：求职者的真实姓名
               - 常见位置：简历开头、个人信息栏
               - 常见说法：姓名、名字、联系人
            
            2. phone（手机号）：联系电话
               - 常见说法：电话、手机、联系方式、Tel、Phone、Mobile
               - 11位手机号或带区号的座机号
            
            3. email（邮箱）：电子邮箱地址
               - 常见说法：邮箱、Email、E-mail、邮件、电子邮箱
            
            4. desired_position（期望职位）：想找的工作岗位
               - 常见说法：求职意向、期望职位、目标职位、应聘岗位、意向岗位、想做
               - 示例："Java后端开发工程师"、"前端开发"
            
            5. desired_city（期望城市）：想工作的城市
               - 常见说法：期望城市、工作地点、意向城市、期望地点、想去哪
            
            6. salary_range（期望薪资）：期望月薪
               - 常见说法：期望薪资、待遇要求、薪资要求、期望薪酬、要价
               - 格式："Xk-Yk"，如"15k-25k"
            
            7. experience_years（工作年限）：全职工作总年数（整数）
               - 常见说法：工作经验、工作年限、工龄、从业年限、工作几年
               - ⚠️ 重要区分：
                 • "工作经验/工作经历"= 曾就职的公司、职位、时间 → 提取总年数填到这里，详细公司信息放到 work_history
                 • "项目经验/项目经历"= 具体做过的项目 → 放到 project_history，不要算入工作年限
               - 如果简历写了"2021.06 - 至今"，用当前年份减去开始年份计算
               - 多段工作经历，年数相加求和
            
            8. education（最高学历）：
               - 常见说法：教育背景、学历、毕业院校、教育经历
               - 标准值："小学"、"初中"、"高中"、"中专"、"大专"、"本科"、"硕士"、"博士"
               - "研究生"统一填"硕士"，"MBA"填"硕士"
            
            9. skills（技能列表）：掌握的技术、工具、语言、能力
               - 常见说法：专业技能、技能特长、掌握技能、技术栈、工具、语言能力
               - 每个技能一个短标签
               - 示例：["Java", "Spring", "MySQL", "Redis", "Git"]
            
            10. summary（个人简介）：自我评价、个人总结
                - 常见说法：自我评价、个人简介、个人描述、个人总结、自我介绍
                - 通常在简历开头或结尾的一段话
            
            11. work_history（工作经历列表）：曾就职的公司信息，每一条是一段工作经历的完整描述
                - 常见说法：工作经历、工作经验、职业经历、就业经历
                - 每条应包含：时间段 + 公司名 + 职位 + 主要职责/业绩（一句话概括）
                - 示例：["2021.06-2023.08  阿里巴巴  Java开发工程师  负责电商系统后台接口开发"]
            
            12. project_history（项目经历列表）：参与过的具体项目，每一条是一个项目的完整描述
                - 常见说法：项目经验、项目经历、项目实践、做过的项目
                - ⚠️ 注意：这是和 work_history 不同的字段！
                  • work_history = 在哪家公司上班（职业经历）
                  • project_history = 在公司/学校里具体做了什么项目
                - 每条应包含：项目名 + 时间 + 你的角色 + 项目描述/技术栈
                - 示例：["2022.03-2022.09  电商订单系统  后端开发  使用Spring Boot+MySQL开发高并发订单模块，支持日单量10万+"]
            
            【歧义处理规则】
            • 简历中"经验"二字出现时：
              - 前面有"工作"= work_history / experience_years
              - 前面有"项目"= project_history
              - 单独出现"经验"且上下文在说技能=放到 skills
            • 分不清一段内容是工作经历还是项目经历时：
              - 有公司名=work_history
              - 有项目名、技术栈、项目描述=project_history
              - 两者都有=分别提取，公司信息放work_history，项目信息放project_history
            • 教育经历有多条时，取最高学历填到 education
            • ⚠️ 任何时候拿不准，就留空，不要猜！
            
            【输出要求】
            1. 只返回 JSON，不要有任何额外文字或 Markdown 标记
            2. 所有字段都必须出现，简历原文中没有的：
               - 字符串字段填 ""（空字符串）
               - 数字字段填 0（代码层会补默认值，你先按实际提取）
               - 数组字段填 []（空数组）
            3. work_history 和 project_history 没有就填空数组 []
            4. salary_range 没写就填""，绝对不要填"0k-0k"
            
            【示例1：信息齐全的简历】
            简历文本："张三  13800138000  zhangsan@email.com
            求职意向：Java开发工程师  期望城市：北京  期望薪资：20k-30k
            工作经验：3年
            教育背景：2015-2019 北京大学 计算机科学与技术 本科
            专业技能：Java, Spring Boot, MySQL, Redis, Git
            工作经历：
            2021.06-至今  字节跳动  Java开发工程师  负责内容推荐系统后台开发
            2019.07-2021.05  百度  Java开发  参与搜索平台接口开发
            项目经验：
            2022.01-2022.06  推荐系统重构项目  后端开发  使用Flink重构实时推荐流，性能提升30%
            自我评价：3年Java开发经验，熟悉互联网后端技术栈，具备高并发系统开发能力。"
            
            正确输出：
            {
              "name": "张三",
              "phone": "13800138000",
              "email": "zhangsan@email.com",
              "desired_position": "Java开发工程师",
              "desired_city": "北京",
              "salary_range": "20k-30k",
              "experience_years": 3,
              "education": "本科",
              "skills": ["Java", "Spring Boot", "MySQL", "Redis", "Git"],
              "summary": "3年Java开发经验，熟悉互联网后端技术栈，具备高并发系统开发能力。",
              "work_history": [
                "2021.06-至今  字节跳动  Java开发工程师  负责内容推荐系统后台开发",
                "2019.07-2021.05  百度  Java开发  参与搜索平台接口开发"
              ],
              "project_history": [
                "2022.01-2022.06  推荐系统重构项目  后端开发  使用Flink重构实时推荐流，性能提升30%"
              ]
            }
            
            【示例2：很简单的简历（只写了姓名和职位）】
            简历文本："李四  求职意向：产品经理"
            
            正确输出（只有这两个字段有值，其他全空）：
            {
              "name": "李四",
              "phone": "",
              "email": "",
              "desired_position": "产品经理",
              "desired_city": "",
              "salary_range": "",
              "experience_years": 0,
              "education": "",
              "skills": [],
              "summary": "",
              "work_history": [],
              "project_history": []
            }
            
            【简历文本】
            %s
            """;

    @Override
    public UserProfile parseFromMessage(String userId, String userMessage) {
        log.info("[行动] 从对话解析求职意向: userId={}, messagePreview={}",
                userId,
                userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage);
        //构造给ai的提示词
        String prompt = String.format(MESSAGE_PARSE_PROMPT, userMessage);
        log.info("[调试] parseFromMessage prompt (前200字): {}",
                prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);
         //调用大模型
        String response = llmService.chat(userId, prompt);
        log.info("[调试] parseFromMessage LLM response: {}", response);
        //把返回出来的json转成java对象
        UserProfile profile = parseJsonToProfile(response, userId);
        //补全默认值
        profile = applyDefaults(profile);
        //存到数据库
        profile = saveProfile(profile);

        log.info("[观察] 对话解析完成: userId={}, desiredPosition={}, desiredCity={}",
                profile.getUserId(), profile.getDesiredPosition(), profile.getDesiredCity());
        return profile;
    }

    @Override
    public UserProfile parseFromFile(String userId, byte[] fileBytes, String fileName) {
        log.info("[行动] 从简历文件解析: userId={}, fileName={}, fileSize={} bytes",
                 userId, fileName, fileBytes.length);
          //从文件中提取纯文本
        String resumeText = extractText(fileBytes, fileName);
        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("无法从文件中提取文本内容：" + fileName);
        }

        if (resumeText.length() > MAX_RESUME_TEXT_LENGTH) {
            log.info("[观察] 简历文本过长已截断: original={}, truncated={}",
                    resumeText.length(), MAX_RESUME_TEXT_LENGTH);
            resumeText = resumeText.substring(0, MAX_RESUME_TEXT_LENGTH);
        }
           //给大模型的提示词
        String prompt = String.format(FILE_PARSE_PROMPT, resumeText);
        log.info("[调试] parseFromFile prompt (前200字): {}",
                prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);
          //调用llm
        String response = llmService.chat(userId, prompt);
        log.info("[调试] parseFromFile LLM response: {}", response);
         //解析JSON数据
        UserProfile profile = parseJsonToProfile(response, userId);
         //保留原始简历文本
        profile.setRawResumeText(resumeText);

        profile = applyDefaults(profile);
        profile = saveProfile(profile);

        log.info("[观察] 文件解析完成: userId={}, name={}, desiredPosition={}",
                profile.getUserId(), profile.getName(), profile.getDesiredPosition());
        return profile;
    }

    @Override
    public UserProfile parseFromImage(String userId, byte[] imageBytes, String fileName) {
        log.info("[行动] 从简历图片解析: userId={}, fileName={}, fileSize={} bytes",
                userId, fileName, imageBytes.length);

        // 第1步：用视觉模型从图片中提取文字（OCR）
        String resumeText = extractTextFromImage(imageBytes, fileName);
        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("无法从图片中识别出文字内容：" + fileName
                    + "，请确认图片是清晰的简历截图");
        }

        log.info("[观察] 图片文字提取完成: textLength={} chars", resumeText.length());

        if (resumeText.length() > MAX_RESUME_TEXT_LENGTH) {
            log.info("[观察] 简历文本过长已截断: original={}, truncated={}",
                    resumeText.length(), MAX_RESUME_TEXT_LENGTH);
            resumeText = resumeText.substring(0, MAX_RESUME_TEXT_LENGTH);
        }

        // 第2步：和文件解析走同样的流程 — 用文本LLM解析结构化JSON
        String prompt = String.format(FILE_PARSE_PROMPT, resumeText);
        log.info("[调试] parseFromImage prompt (前200字): {}",
                prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);

        String response = llmService.chat(userId, prompt);
        log.info("[调试] parseFromImage LLM response: {}", response);

        UserProfile profile = parseJsonToProfile(response, userId);
        profile.setRawResumeText(resumeText);

        profile = applyDefaults(profile);
        profile = saveProfile(profile);

        log.info("[观察] 图片解析完成: userId={}, name={}, desiredPosition={}",
                profile.getUserId(), profile.getName(), profile.getDesiredPosition());
        return profile;
    }

    /**
     * 调用 DashScope 视觉多模态模型，从图片中提取简历文字（OCR）
     */
    private String extractTextFromImage(byte[] imageBytes, String fileName) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mimeType = getImageMimeType(fileName);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        try {
            String ocrPrompt = "这是一张简历图片，请完整准确地识别并输出图片中的所有文字内容。"
                    + "要求：1. 逐行输出，保持原有的换行和段落结构；"
                    + "2. 不要总结、不要解释、不要添加任何额外内容；"
                    + "3. 姓名、电话、邮箱、公司名、学校名等关键信息务必准确识别。";

            List<Map<String, Object>> contentParts = new ArrayList<>();
            contentParts.add(Map.of("type", "text", "text", ocrPrompt));
            contentParts.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUrl)
            ));

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", contentParts)
            );

            Map<String, Object> requestBody = new HashMap<>(Map.of(
                    "model", visionModel,
                    "messages", messages,
                    "max_tokens", 4096
            ));

            String result = callVisionApi(requestBody);
            return result != null ? result.trim() : "";

        } catch (Exception e) {
            log.error("从图片提取文字失败: fileName={}", fileName, e);
            throw new IllegalArgumentException("图片识别失败：" + e.getMessage(), e);
        }
    }

    /**
     * 调用 DashScope 视觉 API（兼容 OpenAI 格式）
     */
    private String callVisionApi(Map<String, Object> requestBody) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(visionApiKey.trim());

        String requestJson = objectMapper.writeValueAsString(requestBody);
        log.info("视觉API请求: model={}, url={}", visionModel, visionBaseUrl);

        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                visionBaseUrl + "/v1/chat/completions", entity, String.class);

        String responseBody = response.getBody();
        if (responseBody == null) {
            throw new IllegalStateException("视觉API返回空响应");
        }

        JsonNode root = objectMapper.readTree(responseBody);
        if (root.has("error")) {
            throw new IllegalStateException("视觉API错误: " + root.get("error"));
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()
                || choices.get(0).path("message").isMissingNode()) {
            throw new IllegalStateException("视觉API响应缺少 choices[0].message");
        }

        return choices.get(0).path("message").path("content").asText("").trim();
    }

    private String getImageMimeType(String fileName) {
        if (fileName == null) return "image/png";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    @Override
    public UserProfile saveProfile(UserProfile profile) {
        if (profile.getUserId() == null || profile.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        String now = OffsetDateTime.now(ZONE).toString();
           //先检查一下，这个用户有没有投过简历
        UserProfile existing = getProfile(profile.getUserId());
        if (existing == null) {
            log.info("[行动] 插入新用户简历: userId={}", profile.getUserId());
            jdbcTemplate.update("""
                    INSERT INTO user_profiles (
                        user_id, name, phone, email, desired_position, desired_city,
                        salary_range, experience_years, education, skills, summary,
                        raw_resume_text, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    profile.getUserId(),
                    profile.getName(),
                    profile.getPhone(),
                    profile.getEmail(),
                    profile.getDesiredPosition(),
                    profile.getDesiredCity(),
                    profile.getSalaryRange(),
                    profile.getExperienceYears(),
                    profile.getEducation(),
                    toJson(profile.getSkills()),
                    profile.getSummary(),
                    profile.getRawResumeText(),
                    now, now
            );
        } else {
            log.info("[行动] 更新用户简历: userId={}", profile.getUserId());
            jdbcTemplate.update("""
                    UPDATE user_profiles SET
                        name = ?, phone = ?, email = ?, desired_position = ?,
                        desired_city = ?, salary_range = ?, experience_years = ?,
                        education = ?, skills = ?, summary = ?, raw_resume_text = ?,
                        updated_at = ?
                    WHERE user_id = ?
                    """,
                    profile.getName(),
                    profile.getPhone(),
                    profile.getEmail(),
                    profile.getDesiredPosition(),
                    profile.getDesiredCity(),
                    profile.getSalaryRange(),
                    profile.getExperienceYears(),
                    profile.getEducation(),
                    toJson(profile.getSkills()),
                    profile.getSummary(),
                    profile.getRawResumeText(),
                    now,
                    profile.getUserId()
            );
        }

        return getProfile(profile.getUserId());
    }
   //从数据库读取
    @Override
    public UserProfile getProfile(String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM user_profiles WHERE user_id = ?", userId);

        if (rows.isEmpty()) {
            return null;
        }
        //取第一条
        Map<String, Object> row = rows.get(0);
        return mapRow(row);
    }
  //把数据库的结果转化为java对象
    private UserProfile mapRow(Map<String, Object> row) {
        return UserProfile.builder()
                .userId((String) row.get("user_id"))
                .name((String) row.get("name"))
                .phone((String) row.get("phone"))
                .email((String) row.get("email"))
                .desiredPosition((String) row.get("desired_position"))
                .desiredCity((String) row.get("desired_city"))
                .salaryRange((String) row.get("salary_range"))
                .experienceYears(row.get("experience_years") != null
                        ? ((Number) row.get("experience_years")).intValue() : null)
                .education((String) row.get("education"))
                .skills(parseSkillsJson((String) row.get("skills")))
                .summary((String) row.get("summary"))
                .rawResumeText((String) row.get("raw_resume_text"))
                .build();
    }

    private String extractText(byte[] fileBytes, String fileName) {
        String lower = fileName != null ? fileName.toLowerCase() : "";

        try {
            if (lower.endsWith(".pdf")) {
                return extractPdfText(fileBytes);
            } else if (lower.endsWith(".docx")) {
                return extractDocxText(fileBytes);
            } else {
                throw new IllegalArgumentException("不支持的文件格式：" + getExtension(fileName)
                        + "，仅支持 .pdf 和 .docx 格式");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("提取简历文本失败: fileName={}", fileName, e);
            throw new IllegalArgumentException("文件解析失败：" + e.getMessage(), e);
        }
    }

    private String extractPdfText(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(bytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            return text != null ? text.trim() : "";
        }
    }

    private String extractDocxText(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> {
                String line = p.getText();
                if (line != null && !line.isBlank()) {
                    sb.append(line).append("\n");
                }
            });
            return sb.toString().trim();
        }
    }

    private UserProfile parseJsonToProfile(String jsonResponse, String userId) {
        try {
            String cleaned = extractJson(jsonResponse);
            JsonNode root = objectMapper.readTree(cleaned);

            List<String> skills = new ArrayList<>();
            JsonNode skillsNode = root.path("skills");
            if (skillsNode.isArray()) {
                for (JsonNode skill : skillsNode) {
                    skills.add(skill.asText(""));
                }
            }

            List<String> workHistory = new ArrayList<>();
            JsonNode workHistoryNode = root.path("work_history");
            if (workHistoryNode.isArray()) {
                for (JsonNode item : workHistoryNode) {
                    workHistory.add(item.asText(""));
                }
            }

            List<String> projectHistory = new ArrayList<>();
            JsonNode projectHistoryNode = root.path("project_history");
            if (projectHistoryNode.isArray()) {
                for (JsonNode item : projectHistoryNode) {
                    projectHistory.add(item.asText(""));
                }
            }

            return UserProfile.builder()
                    .userId(userId)
                    .name(root.path("name").asText(""))
                    .phone(root.path("phone").asText(""))
                    .email(root.path("email").asText(""))
                    .desiredPosition(root.path("desired_position").asText(""))
                    .desiredCity(root.path("desired_city").asText(""))
                    .salaryRange(root.path("salary_range").asText(""))
                    .experienceYears(root.path("experience_years").asInt(0))
                    .education(root.path("education").asText(""))
                    .skills(skills)
                    .summary(root.path("summary").asText(""))
                    .workHistory(workHistory)
                    .projectHistory(projectHistory)
                    .build();

        } catch (JsonProcessingException e) {
            log.error("解析 LLM 返回的 JSON 失败: response={}", jsonResponse, e);
            return UserProfile.builder()
                    .userId(userId)
                    .skills(new ArrayList<>())
                    .build();
        }
    }

    private String extractJson(String response) {
        if (response == null) return "{}";
        String trimmed = response.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private UserProfile applyDefaults(UserProfile profile) {
        if (profile.getDesiredCity() == null || profile.getDesiredCity().isBlank()) {
            profile.setDesiredCity("北京");
        }
        if (profile.getExperienceYears() == null) {
            profile.setExperienceYears(0);
        }
        if (profile.getSkills() == null) {
            profile.setSkills(new ArrayList<>());
        }
        if (profile.getName() == null) profile.setName("");
        if (profile.getPhone() == null) profile.setPhone("");
        if (profile.getEmail() == null) profile.setEmail("");
        if (profile.getDesiredPosition() == null) profile.setDesiredPosition("");
        if (profile.getSalaryRange() == null) profile.setSalaryRange("");
        if (profile.getEducation() == null) profile.setEducation("");
        if (profile.getSummary() == null) profile.setSummary("");
        if (profile.getWorkHistory() == null) profile.setWorkHistory(new ArrayList<>());
        if (profile.getProjectHistory() == null) profile.setProjectHistory(new ArrayList<>());
        return profile;
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("序列化 skills 失败", e);
            return "[]";
        }
    }

    private List<String> parseSkillsJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return list != null ? list : new ArrayList<>();
        } catch (JsonProcessingException e) {
            log.error("反序列化 skills 失败: json={}", json, e);
            return new ArrayList<>();
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "未知";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot).toLowerCase() : "未知";
    }
}
