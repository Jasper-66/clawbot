package com.example.clawbot.resume.client.impl;

import com.example.clawbot.resume.client.ApplicationClient;
import com.example.clawbot.resume.config.ResumePlatformConfig;
import com.example.clawbot.resume.model.ApplicationResult;
import com.example.clawbot.resume.model.JobListing;
import com.example.clawbot.resume.model.UserProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// ── 成员5: 投递执行实现 ──
@Slf4j
@Service
public class ApplicationClientImpl implements ApplicationClient {

    private final ResumePlatformConfig config;
    private final ObjectMapper objectMapper;

    private WebDriver driver;
    private boolean loggedIn = false;

    // Cookie 保存路径
    private static final String COOKIE_DIR = System.getProperty("user.home") + "/.clawbot/cookies";
    private static final String TENCENT_COOKIE_FILE = COOKIE_DIR + "/tencent_cookies.json";

    // 腾讯招聘 URL
    private static final String TENCENT_CAREERS_URL = "https://careers.tencent.com";
    private static final String TENCENT_JOB_DESC_URL = "https://careers.tencent.com/jobdesc.html?postId=";

    public ApplicationClientImpl(ResumePlatformConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public ApplicationResult apply(JobListing job, UserProfile userProfile) {
        String provider = config.getProvider();
        log.info("投递岗位: {} - {} (平台={})", job.getTitle(), job.getCompany(), provider);

        if ("tencent".equals(provider)) {
            return applyByBrowser(job, userProfile);
        }

        return applyMock(job, userProfile);
    }

    @Override
    public List<ApplicationResult> batchApply(List<JobListing> jobs, UserProfile userProfile) {
        String provider = config.getProvider();
        log.info("批量投递开始，共 {} 个岗位 (平台={})", jobs.size(), provider);
        List<ApplicationResult> results = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i++) {
            JobListing job = jobs.get(i);
            try {
                results.add(apply(job, userProfile));

                if (!"mock".equals(provider)) {
                    long delayMs = config.getMinApplyInterval() * 1000L
                            + (long) (Math.random() * (config.getMaxApplyInterval() - config.getMinApplyInterval()) * 1000);
                    log.info("等待 {}ms 后投递下一个 ({}/{})...", delayMs, i + 1, jobs.size());
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("批量投递被中断", e);
                results.add(buildFailResult(job, "投递被中断: " + e.getMessage()));
            } catch (Exception e) {
                log.error("投递失败: {}", job.getTitle(), e);
                results.add(buildFailResult(job, "投递失败: " + e.getMessage()));
            }
        }

        long successCount = results.stream().filter(ApplicationResult::isSuccess).count();
        log.info("批量投递完成: {}/{} 成功", successCount, jobs.size());

        // 批量投递结束后关闭浏览器
        closeBrowser();
        return results;
    }

    @Override
    public ApplicationResult getApplicationStatus(String applicationId) {
        String provider = config.getProvider();
        log.info("查询投递状态: {} (平台={})", applicationId, provider);

        if ("tencent".equals(provider)) {
            return getStatusByBrowser(applicationId);
        }

        return getStatusMock(applicationId);
    }

    // ═══════════════════════════════════════
    // Mock 模式
    // ═══════════════════════════════════════

    private ApplicationResult applyMock(JobListing job, UserProfile profile) {
        String mockId = "MOCK-" + System.currentTimeMillis();
        log.info("[Mock] 模拟投递: {} → {} (applicationId={})", profile.getName(), job.getTitle(), mockId);

        return ApplicationResult.builder()
                .success(true)
                .jobListing(job)
                .applicationId(mockId)
                .status("SUBMITTED")
                .message("Mock投递成功")
                .appliedAt(Instant.now().toString())
                .build();
    }

    private ApplicationResult getStatusMock(String applicationId) {
        return ApplicationResult.builder()
                .success(true)
                .applicationId(applicationId)
                .status("VIEWED")
                .message("Mock: 简历已被查看")
                .build();
    }

    // ═══════════════════════════════════════
    // 腾讯招聘 — 浏览器自动投递
    // ═══════════════════════════════════════

    private ApplicationResult applyByBrowser(JobListing job, UserProfile profile) {
        log.info("[腾讯-浏览器] 投递岗位: {} - {}", job.getTitle(), job.getCompany());

        try {
            // 1. 初始化浏览器
            initBrowser();

            // 2. 确保已登录
            ensureLoggedIn();

            // 3. 打开岗位详情页
            String jobUrl = TENCENT_JOB_DESC_URL + job.getJobId();
            log.info("[腾讯-浏览器] 打开岗位页面: {}", jobUrl);
            driver.get(jobUrl);
            Thread.sleep(3000);  // 等待 SPA 页面加载完成

            // 4. 检查是否已投递
            if (isAlreadyApplied()) {
                log.info("[腾讯-浏览器] 该岗位已投递: {}", job.getTitle());
                return ApplicationResult.builder()
                        .success(true)
                        .jobListing(job)
                        .applicationId(job.getJobId())
                        .status("ALREADY_APPLIED")
                        .message("已投递过该岗位")
                        .appliedAt(Instant.now().toString())
                        .build();
            }

            // 5. 点击投递按钮
            boolean clicked = clickApplyButton();
            if (!clicked) {
                return buildFailResult(job, "未找到投递按钮，页面结构可能已变化");
            }

            // 6. 等待投递结果
            Thread.sleep(2000);
            String resultMessage = getApplyResultMessage();
            log.info("[腾讯-浏览器] 投递结果: {}", resultMessage);

            return ApplicationResult.builder()
                    .success(true)
                    .jobListing(job)
                    .applicationId(job.getJobId())
                    .status("SUBMITTED")
                    .message(resultMessage)
                    .appliedAt(Instant.now().toString())
                    .build();

        } catch (Exception e) {
            log.error("[腾讯-浏览器] 投递失败: {}", e.getMessage(), e);
            return buildFailResult(job, "浏览器投递失败: " + e.getMessage());
        }
    }

    private ApplicationResult getStatusByBrowser(String applicationId) {
        // 浏览器模式下，投递状态通过投递记录表查询，这里返回占位结果
        return ApplicationResult.builder()
                .success(true)
                .applicationId(applicationId)
                .status("SUBMITTED")
                .message("已通过浏览器投递，状态请查询投递记录")
                .build();
    }

    // ═══════════════════════════════════════
    // 浏览器管理
    // ═══════════════════════════════════════

    private void initBrowser() {
        if (driver != null) {
            return;
        }

        log.info("[腾讯-浏览器] 启动 Chrome 浏览器...");
        ChromeOptions options = new ChromeOptions();

        // 反检测设置
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-infobars");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");
        options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

        // 如果配置了无头模式
        if (config.isBrowserHeadless()) {
            options.addArguments("--headless=new");
        }

        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        driver.manage().window().maximize();

        log.info("[腾讯-浏览器] Chrome 启动成功");
    }

    private void closeBrowser() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                log.warn("关闭浏览器异常: {}", e.getMessage());
            }
            driver = null;
            loggedIn = false;
        }
    }

    // ═══════════════════════════════════════
    // 登录管理
    // ═══════════════════════════════════════

    private void ensureLoggedIn() throws InterruptedException {
        if (loggedIn) {
            return;
        }

        // 1. 先访问主页
        driver.get(TENCENT_CAREERS_URL);
        Thread.sleep(2000);

        // 2. 尝试加载已保存的 Cookie
        loadCookies();
        driver.navigate().refresh();
        Thread.sleep(2000);

        // 3. 检查是否已登录
        if (isLoggedIn()) {
            log.info("[腾讯-浏览器] Cookie 有效，已自动登录");
            loggedIn = true;
            return;
        }

        // 4. Cookie 失效，提示用户手动登录
        log.info("══════════════════════════════════════════════");
        log.info("  请在弹出的浏览器窗口中手动登录腾讯招聘");
        log.info("  登录后程序会自动继续...");
        log.info("══════════════════════════════════════════════");

        // 跳转到登录页
        driver.get(TENCENT_CAREERS_URL + "/login");
        Thread.sleep(2000);

        // 等待用户登录（最多等 5 分钟）
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMinutes(5));
        try {
            wait.until(d -> isLoggedIn());
            log.info("[腾讯-浏览器] 登录成功！保存 Cookie...");
            saveCookies();
            loggedIn = true;
        } catch (TimeoutException e) {
            throw new RuntimeException("登录超时，请重新运行程序");
        }
    }

    private boolean isLoggedIn() {
        try {
            // 腾讯招聘登录后，页面上会出现用户相关的元素
            // 通过检测页面源码中是否包含登录状态标志来判断
            String pageSource = driver.getPageSource();

            // 已登录的标志：页面包含"退出"、"我的投递"等文字
            // 未登录的标志：页面包含"登录"按钮
            boolean hasLogout = pageSource.contains("退出") || pageSource.contains("我的投递");
            boolean hasLoginBtn = pageSource.contains("登录") && !hasLogout;

            return hasLogout && !hasLoginBtn;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════
    // Cookie 持久化
    // ═══════════════════════════════════════

    private void saveCookies() {
        try {
            Path dir = Path.of(COOKIE_DIR);
            Files.createDirectories(dir);

            // 将 Cookie 转为 Map 列表保存（Selenium Cookie 类无默认构造函数，不能直接序列化）
            Set<Cookie> cookies = driver.manage().getCookies();
            List<java.util.Map<String, Object>> cookieList = new ArrayList<>();
            for (Cookie c : cookies) {
                java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("name", c.getName());
                map.put("value", c.getValue());
                map.put("domain", c.getDomain());
                map.put("path", c.getPath());
                if (c.getExpiry() != null) {
                    map.put("expiry", c.getExpiry().getTime());
                }
                map.put("secure", c.isSecure());
                map.put("httpOnly", c.isHttpOnly());
                cookieList.add(map);
            }

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(TENCENT_COOKIE_FILE), cookieList);
            log.info("[腾讯-浏览器] Cookie 已保存到: {} (共{}个)", TENCENT_COOKIE_FILE, cookieList.size());
        } catch (IOException e) {
            log.error("保存 Cookie 失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadCookies() {
        File cookieFile = new File(TENCENT_COOKIE_FILE);
        if (!cookieFile.exists()) {
            log.info("[腾讯-浏览器] 未找到 Cookie 文件，需要手动登录");
            return;
        }

        try {
            List<java.util.Map<String, Object>> cookieList = objectMapper.readValue(cookieFile,
                    new TypeReference<List<java.util.Map<String, Object>>>() {});

            for (java.util.Map<String, Object> map : cookieList) {
                try {
                    String name = (String) map.get("name");
                    String value = (String) map.get("value");
                    String domain = (String) map.get("domain");
                    String path = (String) map.getOrDefault("path", "/");
                    boolean secure = (Boolean) map.getOrDefault("secure", false);
                    boolean httpOnly = (Boolean) map.getOrDefault("httpOnly", false);

                    Cookie.Builder builder = new Cookie.Builder(name, value)
                            .domain(domain)
                            .path(path)
                            .isSecure(secure)
                            .isHttpOnly(httpOnly);

                    if (map.containsKey("expiry")) {
                        long expiryMs = ((Number) map.get("expiry")).longValue();
                        builder.expiresOn(new java.util.Date(expiryMs));
                    }

                    driver.manage().addCookie(builder.build());
                } catch (Exception e) {
                    // 某些 Cookie 可能已过期或域名不匹配，跳过
                }
            }
            log.info("[腾讯-浏览器] 已加载 {} 个 Cookie", cookieList.size());
        } catch (IOException e) {
            log.error("加载 Cookie 失败: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════
    // 页面操作
    // ═══════════════════════════════════════

    private boolean isAlreadyApplied() {
        try {
            // 查找页面上包含"已投递"文字的元素
            List<WebElement> elements = driver.findElements(By.xpath("//*[contains(text(),'已投递')]"));
            return !elements.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clickApplyButton() {
        try {
            // 方案1: 按按钮文字查找"投递简历"按钮
            List<WebElement> buttons = driver.findElements(
                    By.xpath("//button[contains(text(),'投递')] | //a[contains(text(),'投递')]"));

            if (!buttons.isEmpty()) {
                WebElement applyBtn = buttons.get(0);
                log.info("[腾讯-浏览器] 找到投递按钮: {}", applyBtn.getText());

                // 滚动到按钮位置
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", applyBtn);
                Thread.sleep(500);

                // 点击按钮
                applyBtn.click();
                log.info("[腾讯-浏览器] 已点击投递按钮");
                return true;
            }

            // 方案2: 查找带有特定 class 的按钮
            buttons = driver.findElements(By.cssSelector(".apply-btn, .btn-apply, [class*='apply']"));
            if (!buttons.isEmpty()) {
                WebElement applyBtn = buttons.get(0);
                log.info("[腾讯-浏览器] 找到投递按钮(class): {}", applyBtn.getAttribute("class"));
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", applyBtn);
                Thread.sleep(500);
                applyBtn.click();
                log.info("[腾讯-浏览器] 已点击投递按钮");
                return true;
            }

            log.warn("[腾讯-浏览器] 未找到投递按钮，当前页面URL: {}", driver.getCurrentUrl());
            return false;

        } catch (Exception e) {
            log.error("[腾讯-浏览器] 点击投递按钮失败: {}", e.getMessage());
            return false;
        }
    }

    private String getApplyResultMessage() {
        try {
            // 等待页面弹出提示
            Thread.sleep(1000);

            // 查找常见的提示元素
            List<WebElement> toasts = driver.findElements(
                    By.cssSelector(".toast, .message, .tip, .alert, [class*='success'], [class*='tip']"));

            if (!toasts.isEmpty()) {
                return toasts.get(0).getText();
            }

            // 检查页面是否出现"已投递"标志
            if (isAlreadyApplied()) {
                return "投递成功（页面显示已投递）";
            }

            return "投递操作已执行，请在腾讯招聘官网查看投递状态";
        } catch (Exception e) {
            return "投递操作已执行，无法获取结果提示";
        }
    }

    // ═══════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════

    private ApplicationResult buildFailResult(JobListing job, String message) {
        return ApplicationResult.builder()
                .success(false)
                .jobListing(job)
                .message(message)
                .appliedAt(Instant.now().toString())
                .build();
    }
}
