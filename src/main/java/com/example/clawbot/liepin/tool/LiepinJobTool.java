package com.example.clawbot.liepin.tool;

import com.example.clawbot.liepin.model.ApplyMode;
import com.example.clawbot.liepin.model.LiepinApplication;
import com.example.clawbot.liepin.model.LiepinApplyResult;
import com.example.clawbot.liepin.model.LiepinJob;
import com.example.clawbot.liepin.service.LiepinJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiepinJobTool {

    private final LiepinJobService liepinJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("[猎聘工具] LiepinJobTool 初始化开始...");
        log.info("[猎聘工具] liepinJobService: {}", liepinJobService != null ? "已注入" : "未注入");
        log.info("[猎聘工具] 已注册工具: search_liepin_jobs, apply_liepin_job, batch_apply_liepin, get_liepin_applications, set_apply_mode");
        log.info("[猎聘工具] LiepinJobTool 初始化完成");
        log.info("========================================");
    }

    @Tool(name = "search_liepin_jobs", description = "【必须调用】搜索猎聘平台的真实招聘信息。当用户提到找工作、求职、搜索岗位、投递简历时，必须调用此工具获取真实职位数据，不要自己编造职位信息。返回结果包含职位ID（方括号内的数字），投递时需要使用。")
    public String searchJobs(
            @ToolParam(description = "职位关键词，如 Java开发、产品经理") String keyword,
            @ToolParam(description = "城市名称，如 北京、上海") String city,
            @ToolParam(required = false, description = "页码，0=第1页") Integer page) {
        
        log.info("[猎聘工具] 搜索职位: keyword={}, city={}, page={}", keyword, city, page);
        
        try {
            int effectivePage = page != null ? page : 0;
            List<LiepinJob> jobs = liepinJobService.searchJobs(keyword, city, effectivePage);
            
            if (jobs.isEmpty()) {
                return "未找到符合条件的职位，请尝试其他关键词或城市。";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("为您找到以下").append(jobs.size()).append("个职位：\n\n");
            
            for (int i = 0; i < jobs.size(); i++) {
                LiepinJob job = jobs.get(i);
                sb.append("【").append(job.getJobId()).append("】");
                sb.append(job.getTitle());
                if (job.getCompany() != null && !job.getCompany().isEmpty()) {
                    sb.append(" | ").append(job.getCompany());
                }
                if (job.getCity() != null && !job.getCity().isEmpty()) {
                    sb.append(" | ").append(job.getCity());
                }
                if (job.getSalary() != null && !job.getSalary().isEmpty()) {
                    sb.append(" | ").append(job.getSalary());
                }
                sb.append("\n");
                
                if (job.getExperience() != null && !job.getExperience().isEmpty()) {
                    sb.append("   经验: ").append(job.getExperience());
                }
                if (job.getEducation() != null && !job.getEducation().isEmpty()) {
                    sb.append(" | 学历: ").append(job.getEducation());
                }
                sb.append("\n\n");
            }
            
            sb.append("投递时请使用职位ID（方括号中的数字），如「投递 1984218471」\n");
            sb.append("回复「全部投递」批量投递所有职位");
            
            return sb.toString();
        } catch (Exception e) {
            log.error("[猎聘工具] 搜索失败: {}", e.getMessage(), e);
            return "搜索失败: " + e.getMessage();
        }
    }

    @Tool(name = "apply_liepin_job", description = "【必须调用】投递简历到猎聘岗位（使用猎聘内置简历）。当用户说投递某个职位时，必须调用此工具执行真实投递。job_id必须是搜索结果中方括号内的职位ID（如1984218471），不是列表序号。job_kind必须从搜索结果中获取。")
    public String applyJob(
            @ToolParam(description = "猎聘职位ID（搜索结果中方括号内的数字，如1984218471）") String job_id,
            @ToolParam(description = "职位类型编号（从搜索结果中获取，必须是1或2）") String job_kind) {
        
        log.info("[猎聘工具] 投递职位: jobId={}, jobKind={}", job_id, job_kind);
        
        try {
            LiepinApplyResult result = liepinJobService.applyJob(job_id, job_kind);
            
            if (result.isSuccess()) {
                return "✅ 投递成功！\n职位ID: " + job_id + "\n状态: 已投递\n\n可在猎聘APP查看投递记录";
            } else {
                return "❌ 投递失败\n职位ID: " + job_id + "\n原因: " + result.getMessage();
            }
        } catch (Exception e) {
            log.error("[猎聘工具] 投递失败: {}", e.getMessage(), e);
            return "投递失败: " + e.getMessage();
        }
    }

    @Tool(name = "batch_apply_liepin", description = "【必须调用】批量投递猎聘岗位。当用户说全部投递、批量投递时，必须调用此工具执行真实投递。job_ids必须是搜索结果中方括号内的职位ID列表。")
    public String batchApply(
            @ToolParam(description = "猎聘职位ID列表（搜索结果中方括号内的数字）") List<String> job_ids,
            @ToolParam(description = "职位类型编号列表（与job_ids一一对应，必须是1或2）") List<String> job_kinds) {
        
        log.info("[猎聘工具] 批量投递: {} 个职位", job_ids.size());
        
        try {
            List<LiepinApplyResult> results = liepinJobService.batchApply(job_ids, job_kinds);
            
            long successCount = results.stream().filter(LiepinApplyResult::isSuccess).count();
            
            StringBuilder sb = new StringBuilder();
            sb.append("批量投递完成：\n");
            sb.append("✅ 成功: ").append(successCount).append(" 个\n");
            sb.append("❌ 失败: ").append(results.size() - successCount).append(" 个");
            
            return sb.toString();
        } catch (Exception e) {
            log.error("[猎聘工具] 批量投递失败: {}", e.getMessage(), e);
            return "批量投递失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_liepin_applications", description = "【必须调用】查询猎聘投递记录。当用户问投了多少家、投递记录、求职进度时，必须调用此工具获取真实投递记录。")
    public String getApplications() {
        log.info("[猎聘工具] 查询投递记录");
        
        try {
            List<LiepinApplication> applications = liepinJobService.getApplicationHistory();
            
            if (applications.isEmpty()) {
                return "暂无投递记录";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("投递记录（共").append(applications.size()).append("条）：\n\n");
            
            for (LiepinApplication app : applications) {
                sb.append("• ");
                if (app.getJobTitle() != null && !app.getJobTitle().isEmpty()) {
                    sb.append(app.getJobTitle());
                } else {
                    sb.append("职位ID: ").append(app.getJobId());
                }
                if (app.getCompany() != null && !app.getCompany().isEmpty()) {
                    sb.append(" @ ").append(app.getCompany());
                }
                sb.append("\n");
                sb.append("  状态: ").append(app.getStatus());
                sb.append(" | 时间: ").append(app.getAppliedAt());
                if (app.getErrorMessage() != null) {
                    sb.append("\n  失败原因: ").append(app.getErrorMessage());
                }
                sb.append("\n\n");
            }
            
            return sb.toString();
        } catch (Exception e) {
            log.error("[猎聘工具] 查询投递记录失败: {}", e.getMessage(), e);
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool(name = "set_apply_mode", description = "设置默认投递模式: manual=手动确认, auto=全自动")
    public String setApplyMode(String mode) {
        log.info("[猎聘工具] 设置投递模式: {}", mode);
        
        try {
            ApplyMode applyMode = ApplyMode.valueOf(mode.toUpperCase());
            liepinJobService.setApplyMode(applyMode);
            
            if (applyMode == ApplyMode.AUTO) {
                return "✅ 已切换到全自动投递模式\n💡 后续搜索到的岗位将自动投递，无需确认\n💡 发送「关闭自动投递」可切换回手动模式";
            } else {
                return "✅ 已切换到手动投递模式\n💡 搜索岗位后需要确认才会投递";
            }
        } catch (IllegalArgumentException e) {
            return "无效的模式，请使用 manual（手动）或 auto（全自动）";
        } catch (Exception e) {
            log.error("[猎聘工具] 设置投递模式失败: {}", e.getMessage(), e);
            return "设置失败: " + e.getMessage();
        }
    }
}
