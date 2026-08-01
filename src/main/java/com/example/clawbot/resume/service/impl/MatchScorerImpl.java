package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.service.MatchScorer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
//对单个岗位进行匹配评分。
public class MatchScorerImpl implements MatchScorer {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MatchScorerImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public int score(UserProfile profile, JobListing job) {
        log.info("开始评分: 用户={}, 岗位={}", profile.getName(), job.getTitle());

        String prompt = buildScorePrompt(profile, job);

        try {
            String response = chatClient.prompt()
                    .system("你是一个专业的招聘匹配评分专家。请严格按照要求返回JSON格式的评分结果，不要添加任何其他文字。")
                    .user(prompt)
                    .call()
                    .content();

            return parseScore(response);
        } catch (Exception e) {
            log.error("LLM评分调用失败: job={}", job.getTitle(), e);
            return 0;
        }
    }

    @Override
    //批量评分并排序。
    public Map<JobListing, Integer> scoreAndRank(UserProfile profile, List<JobListing> jobs) {
        log.info("批量评分: 用户={}, 岗位数={}", profile.getName(), jobs.size());

        Map<JobListing, Integer> scoredJobs = new LinkedHashMap<>();

        for (JobListing job : jobs) {
            scoredJobs.put(job, score(profile, job));
        }

        // 按分数降序排列
        Map<JobListing, Integer> sorted = scoredJobs.entrySet().stream()
                .sorted(Map.Entry.<JobListing, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        log.info("评分完成: {}个岗位", sorted.size());
        return sorted;
    }

    // 评分权重配置：核心三项占75分，其余四项占25分
    private static final double WEIGHT_INTERNSHIP = 25.0;   // 垂直实习
    private static final double WEIGHT_SCHOOL = 25.0;       // 学校层次
    private static final double WEIGHT_PROJECT = 25.0;      // 项目经验
    private static final double WEIGHT_SKILL = 8.0;         // 技能匹配
    private static final double WEIGHT_EDUCATION = 7.0;     // 学历层次
    private static final double WEIGHT_SALARY = 5.0;        // 薪资匹配
    private static final double WEIGHT_CITY = 5.0;          // 城市匹配

    private String buildScorePrompt(UserProfile profile, JobListing job) {
        return String.format("""
                请对以下求职者与岗位的匹配度进行评分。请仔细阅读求职者的项目经历和工作/实习经历描述，判断与目标岗位的相关性。

                【求职者信息】
                姓名: %s
                期望职位: %s
                期望城市: %s
                期望薪资: %s
                工作经验: %d年
                学历: %s
                学校: %s（%s）
                技能: %s
                个人总结: %s
                实习/工作经历: %s
                项目经历: %s

                【岗位信息】
                岗位名称: %s
                公司: %s
                城市: %s
                薪资: %s
                经验要求: %s
                学历要求: %s
                技能要求: %s
                岗位描述: %s

                【评分维度与权重】(总分100分)

                === 核心维度（占比75%%）===
                1. 垂直实习（满分%d分）: 是否有与目标岗位直接相关的实习或工作经历？相关度越高、时长越长、公司越知名，分数越高。无相关实习得0分。
                2. 学校层次（满分%d分）: 985/双一流得满分，211得20分，普通一本得15分，二本得10分，其他得5分。海外名校参考国内985。
                3. 项目经验（满分%d分）: 项目与岗位的相关性、技术栈匹配度、项目复杂度和成果。无相关项目得0分。

                === 辅助维度（占比25%%）===
                4. 技能匹配（满分%d分）: 简历技能与岗位要求的重合度
                5. 学历层次（满分%d分）: 博士满分，硕士6分，本科5分，大专3分
                6. 薪资匹配（满分%d分）: 期望薪资与岗位薪资范围的匹配度
                7. 城市匹配（满分%d分）: 期望城市与岗位城市是否一致

                请严格按以下JSON格式返回，每个维度给具体分数:
                {"internship": 分数, "school": 分数, "project": 分数, "skill": 分数, "education": 分数, "salary": 分数, "city": 分数, "reason": "简短评语（说明核心优势和短板）"}
                """,
                profile.getName() != null ? profile.getName() : "未知",
                profile.getDesiredPosition() != null ? profile.getDesiredPosition() : "未指定",
                profile.getDesiredCity() != null ? profile.getDesiredCity() : "未指定",
                profile.getSalaryRange() != null ? profile.getSalaryRange() : "未指定",
                profile.getExperienceYears() != null ? profile.getExperienceYears() : 0,
                profile.getEducation() != null ? profile.getEducation() : "未指定",
                profile.getSchool() != null ? profile.getSchool() : "未知",
                profile.getSchoolTier() != null ? profile.getSchoolTier() : "未知",
                profile.getSkills() != null ? String.join(", ", profile.getSkills()) : "无",
                profile.getSummary() != null ? profile.getSummary() : "无",
                formatList(profile.getWorkHistory()),
                formatList(profile.getProjectHistory()),
                job.getTitle() != null ? job.getTitle() : "未知",
                job.getCompany() != null ? job.getCompany() : "未知",
                job.getCity() != null ? job.getCity() : "未知",
                job.getSalary() != null ? job.getSalary() : "未指定",
                job.getExperienceRequired() != null ? job.getExperienceRequired() : "未指定",
                job.getEducationRequired() != null ? job.getEducationRequired() : "未指定",
                job.getRequiredSkills() != null ? String.join(", ", job.getRequiredSkills()) : "无",
                job.getDescription() != null ? job.getDescription().substring(0, Math.min(job.getDescription().length(), 500)) : "无",
                (int) WEIGHT_INTERNSHIP, (int) WEIGHT_SCHOOL, (int) WEIGHT_PROJECT,
                (int) WEIGHT_SKILL, (int) WEIGHT_EDUCATION, (int) WEIGHT_SALARY, (int) WEIGHT_CITY
        );
    }

    private String formatList(List<String> list) {
        if (list == null || list.isEmpty()) return "无";
        return String.join("\n---\n", list);
    }

    private int parseScore(String response) {
        if (response == null || response.trim().isEmpty()) {
            log.warn("LLM返回空响应");
            return 0;
        }

        try {
            String json = response.trim();
            if (json.contains("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            JsonNode root = objectMapper.readTree(json);

            // 按权重加权计算总分
            double total = 0;
            total += clamp(root, "internship", WEIGHT_INTERNSHIP);
            total += clamp(root, "school", WEIGHT_SCHOOL);
            total += clamp(root, "project", WEIGHT_PROJECT);
            total += clamp(root, "skill", WEIGHT_SKILL);
            total += clamp(root, "education", WEIGHT_EDUCATION);
            total += clamp(root, "salary", WEIGHT_SALARY);
            total += clamp(root, "city", WEIGHT_CITY);

            int score = (int) Math.round(Math.max(0, Math.min(100, total)));

            String reason = root.has("reason") ? root.get("reason").asText() : "";
            log.info("评分结果: total={}, internship={}, school={}, project={}, reason={}",
                    score,
                    root.path("internship").asInt(0),
                    root.path("school").asInt(0),
                    root.path("project").asInt(0),
                    reason);

            return score;
        } catch (Exception e) {
            log.error("解析LLM评分响应失败: {}", response, e);
            return 0;
        }
    }

    private double clamp(JsonNode root, String field, double max) {
        if (!root.has(field)) return 0;
        double val = root.get(field).asDouble(0);
        return Math.max(0, Math.min(max, val));
    }
}
