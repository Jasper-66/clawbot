package com.example.clawbot.controller;

import com.example.clawbot.exception.Result;
import com.example.clawbot.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderRepository reminderRepository;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public Result<List<Map<String, Object>>> list(
            @RequestParam(value = "status", required = false) String status) {
        String sql;
        if (status != null && !status.isEmpty()) {
            sql = "SELECT * FROM reminders WHERE status = ? ORDER BY trigger_at DESC";
            return Result.success(jdbcTemplate.queryForList(sql, status));
        }
        sql = "SELECT * FROM reminders ORDER BY trigger_at DESC";
        return Result.success(jdbcTemplate.queryForList(sql));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        reminderRepository.markCancelled(id);
        return Result.success(null);
    }
}
