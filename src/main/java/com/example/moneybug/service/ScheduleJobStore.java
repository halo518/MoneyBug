package com.example.moneybug.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** AI 근무표 생성 작업 상태(인메모리). 재시작하면 사라지고, 끝난 지 1시간 지난 작업은 치운다. */
@Component
public class ScheduleJobStore {

    private static final long KEEP_MS = Duration.ofHours(1).toMillis();

    public record JobStatus(String status, String message) {}

    private static final class Job {
        final Integer storeId;
        volatile String status = "running";
        volatile String message = "";
        volatile long finishedAt;

        Job(Integer storeId) { this.storeId = storeId; }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /** 그 매장에 실행 중인 작업이 없을 때만 만든다. 있으면 null. */
    public synchronized String createIfNoneRunning(Integer storeId) {
        if (hasRunning(storeId)) return null;
        long now = System.currentTimeMillis();
        jobs.values().removeIf(j -> j.finishedAt > 0 && now - j.finishedAt > KEEP_MS);
        String id = UUID.randomUUID().toString();
        jobs.put(id, new Job(storeId));
        return id;
    }

    public boolean hasRunning(Integer storeId) {
        return jobs.values().stream().anyMatch(j -> j.storeId.equals(storeId) && "running".equals(j.status));
    }

    /** 그 매장의 작업이 아니거나 없으면 null. */
    public JobStatus get(Integer storeId, String jobId) {
        Job j = jobs.get(jobId);
        return j == null || !j.storeId.equals(storeId) ? null : new JobStatus(j.status, j.message);
    }

    public void done(String jobId, String message) {
        finish(jobId, "done", message);
    }

    public void fail(String jobId, String message) {
        finish(jobId, "failed", message);
    }

    private void finish(String jobId, String status, String message) {
        Job j = jobs.get(jobId);
        if (j == null) return;
        j.message = message == null ? "" : message;
        j.finishedAt = System.currentTimeMillis();
        j.status = status;
    }
}
