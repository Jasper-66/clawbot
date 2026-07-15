package com.example.mission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j // 使用 Lombok 注解生成 log 对象
@Component
public class StartupRunner implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 所有的 Bean 已经加载完毕，项目启动成功！");
    }
}