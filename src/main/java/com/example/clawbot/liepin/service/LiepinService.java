package com.example.clawbot.liepin.service;

import com.example.clawbot.liepin.client.LiepinApiClient;
import com.example.clawbot.liepin.config.LiepinConfig;
import com.example.clawbot.liepin.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 猎聘业务逻辑层 — 对 MCP 返回数据做格式化和异常兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiepinService {

    private final LiepinApiClient liepinApiClient;
    private final LiepinConfig config;

    /**
     * 搜索职位并格式化为可读文本。
     */
    public String searchJobs(String keyword, String city) {
        if (!config.isEnabled()) {
            return "⚠️ 猎聘 MCP 服务未启用，请在配置中设置 mcp.liepin.enabled=true";
        }

        List<Job> jobs = liepinApiClient.searchJobs(keyword, city);
        if (jobs.isEmpty()) {
            return "🔍 未找到相关职位：keyword=" + keyword + ", city=" + city;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🔍 搜索结果（%s · %s）共 %d 个职位：\n\n", keyword, city, jobs.size()));

        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            sb.append(String.format("【%d】%s\n", i + 1, job.getTitle()));
            sb.append(String.format("   🏢 %s | 📍 %s\n", job.getCompany(), job.getCity()));
            sb.append(String.format("   💰 %s | 📋 %s / %s\n",
                    job.getSalary(), job.getExperience(), job.getEducation()));
            if (job.getDescription() != null && !job.getDescription().isBlank()) {
                String desc = job.getDescription().length() > 80
                        ? job.getDescription().substring(0, 80) + "..."
                        : job.getDescription();
                sb.append(String.format("   📝 %s\n", desc));
            }
            sb.append("\n");
        }

        sb.append("💡 回复「投递第N个」可投递简历，如「投递第1个」");
        return sb.toString();
    }

    /**
     * 投递简历到指定职位。
     */
    public String applyJob(String jobId) {
        if (!config.isEnabled()) {
            return "⚠️ 猎聘 MCP 服务未启用";
        }

        boolean success = liepinApiClient.applyJob(jobId, "0");
        if (success) {
            return "✅ 简历投递成功！\n📝 职位ID：" + jobId + "\n⏰ 请等待企业反馈";
        } else {
            return "❌ 简历投递失败，可能已投递或职位已关闭。jobId=" + jobId;
        }
    }

    /**
     * 获取智能推荐职位 — 使用搜索接口不限定条件。
     */
    public String recommendJobs() {
        if (!config.isEnabled()) {
            return "⚠️ 猎聘 MCP 服务未启用";
        }

        List<Job> jobs = liepinApiClient.searchJobs("", "全国");
        if (jobs.isEmpty()) {
            return "🔍 暂无推荐职位，请完善简历后重试";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🎯 为您推荐 %d 个职位：\n\n", jobs.size()));

        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            sb.append(String.format("【%d】%s\n", i + 1, job.getTitle()));
            sb.append(String.format("   🏢 %s | 📍 %s | 💰 %s\n",
                    job.getCompany(), job.getCity(), job.getSalary()));
            sb.append("\n");
        }

        sb.append("💡 回复「投递第N个」可快速投递");
        return sb.toString();
    }

    /**
     * 查看我的简历。
     */
    public String checkApplications() {
        if (!config.isEnabled()) {
            return "⚠️ 猎聘 MCP 服务未启用";
        }

        String resume = liepinApiClient.getMyResume();
        if (resume == null || resume.isBlank()) {
            return "📋 获取简历失败，请稍后重试";
        }

        return "📋 我的简历：\n" + resume;
    }
}
