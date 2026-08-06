package com.example.clawbot.liepin.client;

import com.example.clawbot.liepin.model.LiepinApplyResult;
import com.example.clawbot.liepin.model.LiepinJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Component
public class LiepinApiClientImpl implements LiepinApiClient {

    private final RestTemplate restTemplate;
    
    @Value("${liepin.api.token}")
    private String token;
    
    @Value("${liepin.api.base-url:https://open-agent.liepin.com}")
    private String baseUrl;

    public LiepinApiClientImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public List<LiepinJob> searchJobs(String keyword, String city, int page) {
        log.info("[猎聘API] 搜索职位: keyword={}, city={}, page={}", keyword, city, page);
        
        HttpHeaders headers = createHeaders();

        Map<String, Object> params = new HashMap<>();
        if (keyword != null && !keyword.isEmpty()) {
            params.put("jobName", keyword);
        }
        if (city != null && !city.isEmpty()) {
            params.put("address", city);
        }
        params.put("page", page);
        
        return doSearch(params);
    }

    @Override
    public List<LiepinJob> searchJobsWithParams(Map<String, Object> params) {
        log.info("[猎聘API] 智能搜索: params={}", params);
        if (!params.containsKey("page")) {
            params.put("page", 0);
        }
        return doSearch(params);
    }
    
    private List<LiepinJob> doSearch(Map<String, Object> params) {
        HttpHeaders headers = createHeaders();
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + "/mcp/search-job",
                HttpMethod.POST,
                new HttpEntity<>(params, headers),
                Map.class
            );
            
            Map<String, Object> body = response.getBody();
            if (body == null) {
                log.warn("[猎聘API] 搜索返回空结果");
                return Collections.emptyList();
            }
            
            return parseJobList(body);
        } catch (Exception e) {
            log.error("[猎聘API] 搜索失败: {}", e.getMessage(), e);
            throw new RuntimeException("猎聘搜索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public LiepinApplyResult applyJob(String jobId, String jobKind) {
        log.info("[猎聘API] 投递职位: jobId={}, jobKind={}", jobId, jobKind);
        
        HttpHeaders headers = createHeaders();
        
        Map<String, Object> params = new HashMap<>();
        params.put("jobId", Integer.parseInt(jobId));
        params.put("jobKind", jobKind);
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + "/mcp/apply-job",
                HttpMethod.POST,
                new HttpEntity<>(params, headers),
                Map.class
            );
            
            Map<String, Object> body = response.getBody();
            log.info("[猎聘API] 投递响应: {}", body);
            return parseApplyResult(body);
        } catch (Exception e) {
            log.error("[猎聘API] 投递失败: {}", e.getMessage(), e);
            return LiepinApplyResult.builder()
                .success(false)
                .message("投递失败: " + e.getMessage())
                .build();
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-user-token", token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private List<LiepinJob> parseJobList(Map<String, Object> body) {
        List<LiepinJob> jobs = new ArrayList<>();
        
        try {
            // 根据猎聘API响应结构解析
            Object data = body.get("data");
            log.info("[猎聘API] 响应data类型: {}", data != null ? data.getClass().getSimpleName() : "null");
            
            if (data instanceof List) {
                List<Map<String, Object>> jobList = (List<Map<String, Object>>) data;
                log.info("[猎聘API] data是List，包含{}个元素", jobList.size());
                if (!jobList.isEmpty()) {
                    log.info("[猎聘API] 第一个元素的所有key: {}", jobList.get(0).keySet());
                }
                for (Map<String, Object> jobMap : jobList) {
                    jobs.add(parseJob(jobMap));
                }
            } else if (data instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) data;
                log.info("[猎聘API] data是Map，keys: {}", dataMap.keySet());
                Object list = dataMap.get("list");
                if (list instanceof List) {
                    List<Map<String, Object>> jobList = (List<Map<String, Object>>) list;
                    log.info("[猎聘API] data.list包含{}个元素", jobList.size());
                    if (!jobList.isEmpty()) {
                        log.info("[猎聘API] 第一个职位的所有key: {}", jobList.get(0).keySet());
                        log.info("[猎聘API] 第一个职位完整数据: {}", jobList.get(0));
                    }
                    for (Map<String, Object> jobMap : jobList) {
                        jobs.add(parseJob(jobMap));
                    }
                }
            } else {
                log.warn("[猎聘API] 未知的data类型: {}", data);
            }
        } catch (Exception e) {
            log.error("[猎聘API] 解析职位列表失败: {}", e.getMessage(), e);
        }
        
        log.info("[猎聘API] 解析到 {} 个职位", jobs.size());
        return jobs;
    }

    @SuppressWarnings("unchecked")
    private LiepinJob parseJob(Map<String, Object> jobMap) {
        // 修复：jobType 是猎聘API返回的字段，需要映射到 jobKind
        String jobKind = getValue(jobMap, "jobKind", "job_kind");
        if (jobKind.isEmpty()) {
            jobKind = String.valueOf(jobMap.getOrDefault("jobType", "2"));
        }
        
        return LiepinJob.builder()
            .jobId(getValue(jobMap, "jobId", "job_id"))
            .jobKind(jobKind)
            .title(getValue(jobMap, "jobName", "title"))
            .company(getValue(jobMap, "compName", "company"))
            .city(getValue(jobMap, "address", "city"))
            .salary(getValue(jobMap, "salary", "salaryDesc"))
            .experience(getValue(jobMap, "workExperience", "experience"))
            .education(getValue(jobMap, "eduLevel", "education"))
            .build();
    }

    private String getValue(Map<String, Object> map, String key1, String key2) {
        return getValue(map, key1, key2, "");
    }

    private String getValue(Map<String, Object> map, String key1, String key2, String defaultValue) {
        Object val = map.get(key1);
        if (val != null) return String.valueOf(val);
        val = map.get(key2);
        if (val != null) return String.valueOf(val);
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private LiepinApplyResult parseApplyResult(Map<String, Object> body) {
        if (body == null) {
            return LiepinApplyResult.builder()
                .success(false)
                .message("服务器返回空响应")
                .build();
        }
        
        try {
            // 提取结果消息
            String message = "";
            Object data = body.get("data");
            if (data instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) data;
                message = String.valueOf(dataMap.getOrDefault("result", ""));
            } else {
                message = getValue(body, "message", "msg");
            }
            
            // 判断成功/失败：根据消息内容判断，而不是errCode
            boolean success = message.contains("成功") && !message.contains("失败");
            
            return LiepinApplyResult.builder()
                .success(success)
                .message(message)
                .build();
        } catch (Exception e) {
            return LiepinApplyResult.builder()
                .success(false)
                .message("解析响应失败: " + e.getMessage())
                .build();
        }
    }
}
