package com.example.clawbot.resume.service.impl;

import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.example.clawbot.resume.service.ResumeOptimizer;
import com.example.clawbot.service.LlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ResumeOptimizerImpl implements ResumeOptimizer {

    private final LlmService llmService;

    public ResumeOptimizerImpl(LlmService llmService) {
        this.llmService = llmService;
    }

    @Override
    //用户简历，目标岗位
    public String generateOptimizationTip(UserProfile profile, JobListing job) {
        log.info("[成员4] 生成简历优化建议: userId={}, jobId={}", profile.getUserId(), job.getJobId());

        String prompt = buildOptimizationPrompt(profile, job);

        try {
            String advice = llmService.chat(profile.getUserId(), prompt);
            log.info("[成员4] 简历优化建议生成完成: {}字符", advice.length());
            return advice;
        } catch (Exception e) {
            log.error("[成员4] 生成简历优化建议失败", e);
            return "抱歉，暂时无法生成简历优化建议，请稍后再试。";
        }
    }

    @Override
    public String generateCustomSummary(UserProfile profile, JobListing job) {
        log.info("[成员4] 生成定制化简历摘要: userId={}, jobId={}", profile.getUserId(), job.getJobId());

        String prompt = buildCustomSummaryPrompt(profile, job);

        try {
            String summary = llmService.chat(profile.getUserId(), prompt);
            log.info("[成员4] 定制化简历摘要生成完成: {}字符", summary.length());
            return summary;
        } catch (Exception e) {
            log.error("[成员4] 生成定制化简历摘要失败", e);
            return "抱歉，暂时无法生成定制化简历摘要，请稍后再试。";
        }
    }

    private String buildOptimizationPrompt(UserProfile profile, JobListing job) {
        return "你是一位腾讯招聘平台的专业简历优化顾问。请根据以下用户简历和目标岗位，" +
                "按照腾讯招聘简历格式要求，分析简历与岗位的差距，给出具体、可操作的优化建议。\n\n" +
                "【腾讯招聘简历格式要求】\n" +
                "1. 基本信息：姓名、手机、邮箱、求职状态（在职/离职/应届）\n" +
                "2. 教育经历：学校、专业、学历、GPA、时间段（精确到月）\n" +
                "3. 工作/实习经历：公司名称、职位、时间段、工作职责（3-5条，突出成果）\n" +
                "4. 项目经历：项目名称、时间段、项目描述、个人职责、项目成果（量化指标）\n" +
                "5. 技能标签：技术栈、工具、框架（按熟练度排序）\n" +
                "6. 自我评价：100-150字，突出核心优势和职业目标\n\n" +
                "【用户简历】\n" +
                "姓名: " + profile.getName() + "\n" +
                "期望职位: " + profile.getDesiredPosition() + "\n" +
                "期望城市: " + profile.getDesiredCity() + "\n" +
                "期望薪资: " + profile.getSalaryRange() + "\n" +
                "工作经验: " + profile.getExperienceYears() + "年\n" +
                "学历: " + profile.getEducation() + "\n" +
                "技能: " + String.join(", ", profile.getSkills()) + "\n" +
                "工作经历:\n" + String.join("\n", profile.getWorkHistory()) + "\n" +
                "项目经历:\n" + String.join("\n", profile.getProjectHistory()) + "\n" +
                "自我介绍:\n" + profile.getSummary() + "\n\n" +
                "【目标岗位】\n" +
                "岗位名称: " + job.getTitle() + "\n" +
                "公司: " + job.getCompany() + "\n" +
                "薪资: " + job.getSalary() + "\n" +
                "城市: " + job.getCity() + "\n" +
                "经验要求: " + job.getExperienceRequired() + "\n" +
                "学历要求: " + job.getEducationRequired() + "\n" +
                "技能要求: " + String.join(", ", job.getRequiredSkills()) + "\n" +
                "岗位描述:\n" + job.getDescription() + "\n\n" +
                "【请按照腾讯招聘简历格式给出以下优化建议】\n" +
                "1. 基本信息优化（是否需要补充求职状态等）\n" +
                "2. 教育经历优化（GPA是否需要补充、时间格式规范）\n" +
                "3. 工作经历优化（如何更好地描述职责和成果，建议使用STAR法则）\n" +
                "4. 项目经历优化（如何突出个人贡献和量化成果）\n" +
                "5. 技能标签优化（如何排序和补充岗位所需技能）\n" +
                "6. 自我评价优化（如何突出核心优势和职业匹配度）\n\n" +
                "请用友好、专业的语气，给出具体、可操作的建议，回复控制在600字以内。";
    }

    private String buildCustomSummaryPrompt(UserProfile profile, JobListing job) {
        return "你是一位腾讯招聘平台的专业简历撰写专家。请根据以下用户简历和目标岗位，" +
                "按照腾讯招聘简历格式，生成一份完整的定制化简历内容。\n\n" +
                "【腾讯招聘简历格式要求】\n" +
                "请生成以下6个部分的定制化内容：\n\n" +
                "1. 【基本信息】\n" +
                "   - 姓名: [保持原样]\n" +
                "   - 手机: [保持原样]\n" +
                "   - 邮箱: [保持原样]\n" +
                "   - 求职状态: [根据用户情况填写：在职/离职/应届]\n" +
                "   - 期望职位: [根据岗位调整]\n" +
                "   - 期望城市: [根据岗位调整]\n" +
                "   - 期望薪资: [根据岗位调整]\n\n" +
                "2. 【教育经历】（格式规范）\n" +
                "   - 学校名称 | 专业 | 学历 | GPA（如3.8/4.0）| 时间段（2020.09-2024.06）\n\n" +
                "3. 【工作/实习经历】（按时间倒序，使用STAR法则）\n" +
                "   公司名称 | 职位 | 时间段（2023.07-至今）\n" +
                "   工作职责：\n" +
                "   • 负责XXX（具体工作内容）\n" +
                "   • 通过XXX方法，实现了XXX成果（量化指标）\n" +
                "   • 参与XXX项目，提升了XXX效率XX%\n\n" +
                "4. 【项目经历】（突出个人贡献和量化成果）\n" +
                "   项目名称 | 时间段（2023.03-2023.06）\n" +
                "   项目描述: [简要描述项目背景和目标]\n" +
                "   个人职责:\n" +
                "   • 负责XXX模块的设计和开发\n" +
                "   • 使用XXX技术栈，解决了XXX问题\n" +
                "   项目成果:\n" +
                "   • 系统性能提升XX%\n" +
                "   • 用户满意度达到XX%\n" +
                "   • 获得XXX奖项/认可\n\n" +
                "5. 【技能标签】（按熟练度排序）\n" +
                "   熟练: Java, Spring Boot, MySQL\n" +
                "   掌握: Redis, RabbitMQ, Docker\n" +
                "   了解: Kubernetes, Jenkins\n\n" +
                "6. 【自我评价】（100-150字，突出核心优势和职业匹配度）\n" +
                "   例如：具备X年Java后端开发经验，熟悉Spring Boot微服务架构，" +
                "有大型分布式系统开发经验。擅长XXX，具备良好的XXX能力。" +
                "对XXX技术有浓厚兴趣，持续关注XXX领域的发展。" +
                "期望在XXX方向深入发展，为团队创造更大价值。\n\n" +
                "【用户简历】\n" +
                "姓名: " + profile.getName() + "\n" +
                "手机号: " + profile.getPhone() + "\n" +
                "邮箱: " + profile.getEmail() + "\n" +
                "期望职位: " + profile.getDesiredPosition() + "\n" +
                "期望城市: " + profile.getDesiredCity() + "\n" +
                "期望薪资: " + profile.getSalaryRange() + "\n" +
                "工作经验: " + profile.getExperienceYears() + "年\n" +
                "学历: " + profile.getEducation() + "\n" +
                "技能: " + String.join(", ", profile.getSkills()) + "\n" +
                "工作经历:\n" + String.join("\n", profile.getWorkHistory()) + "\n" +
                "项目经历:\n" + String.join("\n", profile.getProjectHistory()) + "\n" +
                "自我介绍:\n" + profile.getSummary() + "\n\n" +
                "【目标岗位】\n" +
                "岗位名称: " + job.getTitle() + "\n" +
                "公司: " + job.getCompany() + "\n" +
                "薪资: " + job.getSalary() + "\n" +
                "城市: " + job.getCity() + "\n" +
                "经验要求: " + job.getExperienceRequired() + "\n" +
                "学历要求: " + job.getEducationRequired() + "\n" +
                "技能要求: " + String.join(", ", job.getRequiredSkills()) + "\n" +
                "岗位描述:\n" + job.getDescription() + "\n\n" +
                "【要求】\n" +
                "1. 根据目标岗位JD，突出最匹配的经历和技能\n" +
                "2. 使用STAR法则描述工作职责和项目经历\n" +
                "3. 量化成果（百分比、数字、奖项等）\n" +
                "4. 技能标签按岗位需求调整排序\n" +
                "5. 自我评价突出核心优势和职业匹配度\n" +
                "6. 语言精炼、专业、有吸引力\n" +
                "7. 完全符合腾讯招聘简历格式规范\n\n" +
                "请直接生成完整的定制化简历内容，按上述6个部分组织，不要包含其他说明。";
    }
}
