package com.example.clawbot.repository;

import com.example.clawbot.entity.ScheduledTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, String> {

    List<ScheduledTaskEntity> findByUserIdAndStatus(String userId, String status);

    List<ScheduledTaskEntity> findByStatus(String status);

    List<ScheduledTaskEntity> findByUserId(String userId);
}
