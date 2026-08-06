package com.example.clawbot.liepin.client;

import com.example.clawbot.liepin.model.LiepinApplyResult;
import com.example.clawbot.liepin.model.LiepinJob;

import java.util.List;
import java.util.Map;

public interface LiepinApiClient {
    
    /**
     * 搜索猎聘职位（简单模式）
     * @param keyword 职位关键词
     * @param city 城市（国内或国外）
     * @param page 页码（0=第1页）
     * @return 职位列表
     */
    List<LiepinJob> searchJobs(String keyword, String city, int page);
    
    /**
     * 搜索猎聘职位（完整参数模式，支持智能搜索）
     * @param params 搜索参数Map，支持：
     *               address - 工作地点（自由文本，不限城市）
     *               jobName - 职位关键词
     *               workExperience - 工作经验
     *               eduLevel - 学历要求
     *               salaryFloor - 薪资下限
     *               salaryCap - 薪资上限
     *               salaryKind - 薪资类型（月薪/年薪）
     *               compNature - 公司性质（国企/外企/民营）
     *               companyName - 公司名称
     *               page - 页码
     * @return 职位列表
     */
    List<LiepinJob> searchJobsWithParams(Map<String, Object> params);
    
    /**
     * 投递简历到猎聘岗位
     * @param jobId 职位ID
     * @param jobKind 职位类型编号（必须是"1"或"2"，从搜索结果获取）
     * @return 投递结果
     */
    LiepinApplyResult applyJob(String jobId, String jobKind);
}
