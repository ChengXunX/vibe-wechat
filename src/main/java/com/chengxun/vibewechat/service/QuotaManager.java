package com.chengxun.vibewechat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class QuotaManager {

    private static final int MESSAGE_LIMIT = 10;

    private final Map<String, QuotaState> quotas = new ConcurrentHashMap<>();

    static class QuotaState {
        int totalUsed = 0;
        int reservedForResult = 0;
        int runningProcesses = 0;

        synchronized int getAvailable() {
            // 可用配额 = 10 - 已用 - 运行中的进程（每个忙碌进程占用一条配额）
            return MESSAGE_LIMIT - totalUsed - runningProcesses;
        }
    }

    public void reserveForResult(String userId) {
        QuotaState q = quotas.computeIfAbsent(userId, k -> new QuotaState());
        synchronized (q) {
            q.reservedForResult++;
            log.debug("Reserved result slot for {}: total={}, reserved={}, running={}",
                    userId, q.totalUsed, q.reservedForResult, q.runningProcesses);
        }
    }

    public void releaseResultSlot(String userId) {
        QuotaState q = quotas.get(userId);
        if (q != null) {
            synchronized (q) {
                q.reservedForResult = Math.max(0, q.reservedForResult - 1);
            }
        }
    }

    public boolean canSendToolMessage(String userId) {
        QuotaState q = quotas.get(userId);
        if (q == null) return true;
        synchronized (q) {
            return q.getAvailable() > 0;
        }
    }

    /**
     * 决策消息始终可以发送（不受配额限制）
     */
    public boolean canSendDecisionMessage(String userId) {
        return true;
    }

    /**
     * 获取预留配额数量（等待结果的进程占用的配额）
     */
    public int getReservedQuota(String userId) {
        QuotaState q = quotas.get(userId);
        if (q == null) return 0;
        synchronized (q) {
            return q.reservedForResult;
        }
    }

    public int getRunningProcesses(String userId) {
        QuotaState q = quotas.get(userId);
        if (q == null) return 0;
        synchronized (q) {
            return q.runningProcesses;
        }
    }

    public void recordMessageSent(String userId, String messageType) {
        QuotaState q = quotas.computeIfAbsent(userId, k -> new QuotaState());
        synchronized (q) {
            q.totalUsed++;
            if ("result".equals(messageType)) {
                q.reservedForResult = Math.max(0, q.reservedForResult - 1);
            }
            log.debug("Recorded message for {}: type={}, total={}, reserved={}",
                    userId, messageType, q.totalUsed, q.reservedForResult);
        }
    }

    public void processStarted(String userId) {
        QuotaState q = quotas.computeIfAbsent(userId, k -> new QuotaState());
        synchronized (q) {
            q.runningProcesses++;
        }
    }

    public void processEnded(String userId) {
        QuotaState q = quotas.get(userId);
        if (q != null) {
            synchronized (q) {
                q.runningProcesses = Math.max(0, q.runningProcesses - 1);
            }
        }
    }

    public void reset(String userId) {
        quotas.remove(userId);
    }

    public String getStatus(String userId) {
        QuotaState q = quotas.get(userId);
        if (q == null) return "无数据";
        synchronized (q) {
            int available = q.getAvailable();
            int reserved = Math.min(q.runningProcesses, MESSAGE_LIMIT - 1);
            return String.format("已用:%d, 预留:%d, 可用:%d (共10条)",
                    q.totalUsed, reserved, available);
        }
    }
}
