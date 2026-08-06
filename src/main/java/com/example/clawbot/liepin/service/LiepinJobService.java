package com.example.clawbot.liepin.service;

import com.example.clawbot.liepin.model.ApplyMode;
import com.example.clawbot.liepin.model.LiepinApplication;
import com.example.clawbot.liepin.model.LiepinApplyResult;
import com.example.clawbot.liepin.model.LiepinJob;

import java.util.List;
import java.util.Map;

public interface LiepinJobService {
    
    /**
     * 搜索猎聘职位（简单模式）
     */
    List<LiepinJob> searchJobs(String keyword, String city, int page);
    
    /**
     * 搜索猎聘职位（智能模式，支持完整参数）
     * @param params 搜索参数Map
     */
    List<LiepinJob> searchJobsWithParams(Map<String, Object> params);
    
    /**
     * 投递单个职位
     */
    LiepinApplyResult applyJob(String jobId, String jobKind);
    
    /**
     * 批量投递
     */
    List<LiepinApplyResult> batchApply(List<String> jobIds, List<String> jobKinds);
    
    /**
     * 设置投递模式
     */
    void setApplyMode(ApplyMode mode);
    
    /**
     * 获取当前投递模式
     */
    ApplyMode getApplyMode();
    
    /**
     * 查询投递记录
     */
    List<LiepinApplication> getApplicationHistory();
    
    /**
     * 检查是否已投递过该职位
     */
    boolean hasApplied(String jobId);
}
