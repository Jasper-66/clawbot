package com.example.clawbot.repository;

import com.example.clawbot.entity.ScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    List<ScheduledTask> findByUserIdAndStatusOrderByExecuteAtAsc(String userId, String status);

    List<ScheduledTask> findByStatusAndExecuteAtBefore(String status, LocalDateTime now);

    Optional<ScheduledTask> findByIdAndUserId(Long id, String userId);
}
