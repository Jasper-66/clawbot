package com.example.clawbot.liepin.service;

import com.example.clawbot.liepin.client.LiepinApiClient;
import com.example.clawbot.liepin.model.ApplyMode;
import com.example.clawbot.liepin.model.LiepinApplication;
import com.example.clawbot.liepin.model.LiepinApplyResult;
import com.example.clawbot.liepin.model.LiepinJob;
import com.example.clawbot.liepin.repository.LiepinApplicationRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiepinJobServiceImpl implements LiepinJobService {

    private final LiepinApiClient liepinApiClient;
    private final LiepinApplicationRepository applicationRepository;
    
    private ApplyMode currentMode = ApplyMode.MANUAL;

    @PostConstruct
    public void init() {
        log.info("[猎聘服务] LiepinJobServiceImpl 初始化完成");
        log.info("[猎聘服务] liepinApiClient: {}", liepinApiClient != null ? "已注入" : "未注入");
        log.info("[猎聘服务] applicationRepository: {}", applicationRepository != null ? "已注入" : "未注入");
    }

    @Override
    public List<LiepinJob> searchJobs(String keyword, String city, int page) {
        log.info("[猎聘服务] 搜索职位: keyword={}, city={}, page={}", keyword, city, page);
        try {
            List<LiepinJob> jobs = liepinApiClient.searchJobs(keyword, city, page);
            log.info("[猎聘服务] 搜索成功，找到 {} 个职位", jobs.size());
            return jobs;
        } catch (Exception e) {
            log.error("[猎聘服务] 搜索失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public List<LiepinJob> searchJobsWithParams(Map<String, Object> params) {
        log.info("[猎聘服务] 智能搜索: params={}", params);
        try {
            List<LiepinJob> jobs = liepinApiClient.searchJobsWithParams(params);
            log.info("[猎聘服务] 智能搜索成功，找到 {} 个职位", jobs.size());
            return jobs;
        } catch (Exception e) {
            log.error("[猎聘服务] 智能搜索失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public LiepinApplyResult applyJob(String jobId, String jobKind) {
        log.info("[猎聘服务] 投递职位: jobId={}, jobKind={}", jobId, jobKind);
        
        // 检查是否已投递
        if (applicationRepository.existsByJobId(jobId)) {
            log.info("[猎聘服务] 职位 {} 已投递过，跳过", jobId);
            return LiepinApplyResult.builder()
                .success(false)
                .message("该职位已投递过")
                .build();
        }
        
        try {
            LiepinApplyResult result = liepinApiClient.applyJob(jobId, jobKind);
            
            // 保存投递记录
            LiepinApplication application = LiepinApplication.builder()
                .id(UUID.randomUUID().toString())
                .jobId(jobId)
                .jobKind(jobKind)
                .status(result.isSuccess() ? "已投递" : "失败")
                .appliedAt(LocalDateTime.now())
                .errorMessage(result.isSuccess() ? null : result.getMessage())
                .build();
            applicationRepository.save(application);
            
            log.info("[猎聘服务] 投递结果: success={}, message={}", result.isSuccess(), result.getMessage());
            return result;
        } catch (Exception e) {
            log.error("[猎聘服务] 投递失败: {}", e.getMessage(), e);
            
            // 保存失败记录
            LiepinApplication application = LiepinApplication.builder()
                .id(UUID.randomUUID().toString())
                .jobId(jobId)
                .jobKind(jobKind)
                .status("失败")
                .appliedAt(LocalDateTime.now())
                .errorMessage(e.getMessage())
                .build();
            applicationRepository.save(application);
            
            return LiepinApplyResult.builder()
                .success(false)
                .message("投递失败: " + e.getMessage())
                .build();
        }
    }

    @Override
    public List<LiepinApplyResult> batchApply(List<String> jobIds, List<String> jobKinds) {
        log.info("[猎聘服务] 批量投递: {} 个职位", jobIds.size());
        List<LiepinApplyResult> results = new ArrayList<>();
        
        for (int i = 0; i < jobIds.size(); i++) {
            String jobId = jobIds.get(i);
            String jobKind = jobKinds.get(i);
            LiepinApplyResult result = applyJob(jobId, jobKind);
            results.add(result);
            
            // 避免触发限流，每次投递间隔1秒
            if (i < jobIds.size() - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        long successCount = results.stream().filter(LiepinApplyResult::isSuccess).count();
        log.info("[猎聘服务] 批量投递完成: 成功 {}/{}", successCount, results.size());
        
        return results;
    }

    @Override
    public void setApplyMode(ApplyMode mode) {
        this.currentMode = mode;
        log.info("[猎聘服务] 投递模式切换为: {}", mode);
    }

    @Override
    public ApplyMode getApplyMode() {
        return currentMode;
    }

    @Override
    public List<LiepinApplication> getApplicationHistory() {
        return applicationRepository.findAll();
    }

    @Override
    public boolean hasApplied(String jobId) {
        return applicationRepository.existsByJobId(jobId);
    }
}
