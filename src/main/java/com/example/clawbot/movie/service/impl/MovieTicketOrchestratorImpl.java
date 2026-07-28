package com.example.clawbot.movie.service.impl;

import com.example.clawbot.movie.client.MovieSearchClient;
import com.example.clawbot.movie.client.OrderClient;
import com.example.clawbot.movie.client.SeatClient;
import com.example.clawbot.movie.client.ShowtimeClient;
import com.example.clawbot.movie.model.TicketOrder;
import com.example.clawbot.movie.service.MoviePreferenceParser;
import com.example.clawbot.movie.service.MovieTicketOrchestrator;
import com.example.clawbot.movie.service.SeatSelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// ── 成员7: 流程编排实现（空骨架）──
// 这是整个电影票模块的"指挥家"，串联成员1~6
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieTicketOrchestratorImpl implements MovieTicketOrchestrator {

    // 注入成员1~6的接口（Spring会自动注入实现类）
    private final MoviePreferenceParser preferenceParser;  // 成员1
    private final MovieSearchClient movieSearchClient;     // 成员2
    private final ShowtimeClient showtimeClient;           // 成员3
    private final SeatClient seatClient;                   // 成员4
    private final SeatSelector seatSelector;               // 成员5
    private final OrderClient orderClient;                 // 成员6

    // ── 以下方法全部由成员7实现，按注释中的流程串联调用 ──

    @Override
    public String searchMovies(String userId, String city, String keyword) {
        // TODO 成员7: 按 MovieTicketOrchestrator.searchMovies() 注释实现
        throw new UnsupportedOperationException("TODO: 成员7实现 — 搜索电影并格式化输出");
    }

    @Override
    public TicketOrder autoPurchase(String userId, String userMessage) {
        // TODO 成员7: 按 MovieTicketOrchestrator.autoPurchase() 注释实现
        // 核心：串联 Step1→Step8，每一步调对应成员的方法
        throw new UnsupportedOperationException("TODO: 成员7实现 — 全流程自动购票");
    }

    @Override
    public String searchShowtimes(String movieId, String city, String date) {
        // TODO 成员7: 按 MovieTicketOrchestrator.searchShowtimes() 注释实现
        throw new UnsupportedOperationException("TODO: 成员7实现 — 查询场次并格式化输出");
    }

    @Override
    public boolean requestUserConfirmation(String userId, TicketOrder preview) {
        // TODO 成员7: 按 MovieTicketOrchestrator.requestUserConfirmation() 注释实现
        throw new UnsupportedOperationException("TODO: 成员7实现 — 用户确认交互");
    }
}
