package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.ClaudeConfig;
import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.config.ThinkingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class ClaudeApiService {

    @Autowired
    private ClaudeConfig claudeConfig;

    @Autowired
    private FilterConfig filterConfig;

    @Autowired
    private ThinkingConfig thinkingConfig;

    @Autowired
    private QuotaManager quotaManager;

    // 会话管理（tokenUsageMap 按 sessionId 统计，确保切换 session 后数据独立）
    private final Map<String, TokenUsage> tokenUsageMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessionHistory = new ConcurrentHashMap<>();
    private final Map<String, String> subtaskNames = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // 上下文压缩记忆文档（sessionId -> 文件路径）
    private final Map<String, String> memoryDocuments = new ConcurrentHashMap<>();

    // 磁盘会话缓存
    private List<DiskSession> diskSessions = new ArrayList<>();

    // 常驻进程池
    private final Map<String, List<ClaudeProcess>> processPools = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<PendingRequest>> requestQueues = new ConcurrentHashMap<>();
    // 当前活跃进程索引（用于 v-fork 克隆当前进程）
    private final Map<String, Integer> currentProcessIndex = new ConcurrentHashMap<>();

    // 空闲进程清理调度器
    private ScheduledExecutorService cleanupScheduler;

    @PostConstruct
    public void init() {
        if (claudeConfig.getInstallPath() == null || claudeConfig.getInstallPath().isEmpty()) {
            detectClaudePath();
        }

        // 清理孤儿进程
        cleanupOrphanProcesses();

        // 扫描磁盘会话
        scanDiskSessions();

        // 启动空闲进程清理任务
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-process-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleProcesses, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 服务关闭时销毁所有 Claude 子进程。
     * 正常关闭：进程池清空，所有子进程被 destroyForcibly 终止。
     * 异常关闭（kill -9）：Java 进程被杀，子进程可能成为孤儿进程。
     * 重启后：进程池为空，会话历史丢失，但 ~/.claude/sessions 仍保留磁盘会话。
     */
    @PreDestroy
    public void destroy() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
        }
        // 销毁所有进程组
        processPools.values().forEach(pool ->
            pool.forEach(ClaudeProcess::destroy)
        );
        processPools.clear();
        requestQueues.clear();
    }

    // ==================== 常驻进程内部类 ====================
    // ClaudeProcess 是伪进程（Worker），常驻在进程池中
    // 内部持有的 process/stdin/stderr 是临时的 Claude CLI 进程资源

    static class ClaudeProcess {
        // 临时 Claude CLI 进程资源（每次任务创建，任务完成后销毁）
        Process process;
        OutputStream stdin;
        BufferedReader stdout;
        BufferedReader stderr;

        // Worker 状态（常驻）
        volatile boolean busy = false;
        String sessionId;
        String thinkingLevel;
        String workDir;
        long lastActiveTime = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();

        // Fork 进程组支持
        boolean isParent = false;   // 是否为父进程
        String parentId = null;     // 父进程的 userId（子进程才有）
        boolean isIndependent = false;  // 是否为独立进程（v-newproc 创建）

        /**
         * 检查 Worker 是否有活跃的 Claude CLI 进程
         */
        synchronized boolean hasActiveCliProcess() {
            return process != null && process.isAlive();
        }

        /**
         * 销毁临时 Claude CLI 进程，但保留 Worker
         */
        synchronized void destroyCliProcess() {
            if (process != null) {
                process.destroyForcibly();
                process = null;
            }
            stdin = null;
            stdout = null;
            stderr = null;
        }

        /**
         * 销毁整个 Worker（包括 CLI 进程）
         */
        synchronized void destroy() {
            busy = false;
            destroyCliProcess();
        }
    }

    static class PendingRequest {
        String message;
        CompletableFuture<String> future;

        PendingRequest(String message, CompletableFuture<String> future) {
            this.message = message;
            this.future = future;
        }
    }

    // 磁盘会话信息
    static class DiskSession {
        String sessionId;
        String pid;
        String cwd;
        long startedAt;
        String status;

        DiskSession(String sessionId, String pid, String cwd, long startedAt, String status) {
            this.sessionId = sessionId;
            this.pid = pid;
            this.cwd = cwd;
            this.startedAt = startedAt;
            this.status = status;
        }
    }

    // ==================== 进程管理 ====================

    private ClaudeProcess getOrCreateProcess(String userId) {
        List<ClaudeProcess> pool = processPools.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // 1. 查找组内空闲 Worker（线程空闲，没有在执行任务）
        for (ClaudeProcess p : pool) {
            if (!p.busy) {
                // Worker 空闲，如果它没有活跃的 CLI 进程，为其创建一个
                if (!p.hasActiveCliProcess()) {
                    if (!attachCliProcess(p, userId)) {
                        continue; // 创建失败，尝试下一个空闲进程
                    }
                }
                return p;
            }
        }

        // 2. 检查进程数上限（克隆进程也计入）
        if (pool.size() >= claudeConfig.getMaxProcessesPerUser()) {
            return null; // 需要排队
        }

        // 3. 进程池为空时创建父进程 Worker
        if (pool.isEmpty()) {
            ClaudeProcess cp = createNewWorker(userId, pool, true);
            if (!attachCliProcess(cp, userId)) {
                pool.remove(cp);
                return null; // 创建失败
            }
            return cp;
        }

        // 4. 有 Worker 但全忙：排队等待空闲 Worker
        return null;
    }

    /**
     * 为 Worker 附加一个 Claude CLI 进程
     */
    private boolean attachCliProcess(ClaudeProcess worker, String userId) {
        String installPath = claudeConfig.getInstallPath();
        if (installPath == null || installPath.isEmpty()) {
            return false;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(installPath);
            command.add("--print");
            command.add("--input-format");
            command.add("text");
            command.add("--output-format");
            command.add("stream-json");
            command.add("--verbose");
            command.add("--dangerously-skip-permissions");

            // 模型配置
            String model = claudeConfig.getModel();
            if (model != null && !model.isEmpty()) {
                command.add("--model");
                command.add(model);
            }

            // thinking 配置
            if (thinkingConfig.isEnabled()) {
                String settingsJson = "{\"thinking\":{\"budget_tokens\":" + thinkingConfig.getCurrentBudgetTokens() + "}}";
                command.add("--settings");
                command.add(settingsJson);
            }

            // 使用 Worker 的 session（恢复上下文）
            String sessionId = worker.sessionId;
            if (sessionId != null) {
                command.add("--resume");
                command.add(sessionId);
                log.info("Worker attaching CLI with session: {} for user: {}", sessionId, userId);
            }

            // 使用 Worker 的工作目录
            String workDir = worker.workDir;
            if (workDir == null || workDir.isEmpty()) {
                workDir = claudeConfig.getWorkDir();
            }
            if (workDir == null || workDir.isEmpty()) {
                workDir = System.getProperty("user.dir");
            }

            log.info("Attaching Claude CLI process for worker, user: {}, model: {}", userId, model);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            if (workDir != null && !workDir.isEmpty()) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            worker.process = process;
            worker.stdin = process.getOutputStream();
            worker.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            worker.stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            quotaManager.processStarted(userId);

            return true;

        } catch (Exception e) {
            log.error("Failed to attach Claude CLI process for user: {}", userId, e);
            return false;
        }
    }

    /**
     * 创建新的 Worker（伪进程）
     */
    private ClaudeProcess createNewWorker(String userId, List<ClaudeProcess> pool, boolean isParent) {
        return createNewWorker(userId, pool, isParent, false);
    }

    private ClaudeProcess createNewWorker(String userId, List<ClaudeProcess> pool, boolean isParent, boolean isIndependent) {
        ClaudeProcess worker = new ClaudeProcess();
        worker.isParent = isParent;
        worker.isIndependent = isIndependent;
        worker.thinkingLevel = thinkingConfig.getLevel();

        // 设置工作目录
        String workDir = claudeConfig.getWorkDir();
        if (workDir == null || workDir.isEmpty()) {
            workDir = System.getProperty("user.dir");
        }
        worker.workDir = workDir;

        // 如果有 session，设置到 Worker
        String sessionId = sessionIds.get(userId);
        if (sessionId != null) {
            worker.sessionId = sessionId;
        }

        pool.add(worker);
        log.info("Created new worker for user: {}, isParent: {}", userId, isParent);
        return worker;
    }

    /**
     * Fork 子 Worker：共享父 Worker 的 session 和工作目录
     */
    private ClaudeProcess forkProcess(String userId, List<ClaudeProcess> pool) {
        // 找到父 Worker
        ClaudeProcess parent = null;
        for (ClaudeProcess p : pool) {
            if (p.isParent) {
                parent = p;
                break;
            }
        }

        if (parent == null) {
            log.warn("No parent worker found for user: {}, cannot fork", userId);
            return null;
        }

        // 创建子 Worker，共享父 Worker 的 session 和工作目录
        ClaudeProcess child = new ClaudeProcess();
        child.isParent = false;
        child.parentId = userId;
        child.sessionId = parent.sessionId;
        child.workDir = parent.workDir;
        child.thinkingLevel = parent.thinkingLevel;

        pool.add(child);
        log.info("Forked child worker for user: {}, pool size: {}", userId, pool.size());
        return child;
    }

    /**
     * Fork 子 Worker：使用指定的 session 和工作目录
     */
    private ClaudeProcess forkProcess(String userId, List<ClaudeProcess> pool, String sessionId, String workDir) {
        // 创建子 Worker
        ClaudeProcess child = new ClaudeProcess();
        child.isParent = false;
        child.parentId = userId;
        child.sessionId = sessionId;
        child.workDir = workDir;
        child.thinkingLevel = thinkingConfig.getLevel();

        pool.add(child);
        log.info("Forked child worker for user: {}, pool size: {}", userId, pool.size());
        return child;
    }

    // ==================== 消息发送 ====================

    public CompletableFuture<String> sendMessageAsync(String userId, String message) {
        CompletableFuture<String> future = new CompletableFuture<>();

        // 检查配额
        if (!quotaManager.canSendToolMessage(userId)) {
            future.complete("消息次数已达上限（10条），请等待当前任务完成");
            return future;
        }

        ClaudeProcess cp = getOrCreateProcess(userId);
        if (cp == null) {
            // 进程数达上限，加入队列
            BlockingQueue<PendingRequest> queue = requestQueues.computeIfAbsent(userId,
                    k -> new LinkedBlockingQueue<>());
            queue.offer(new PendingRequest(message, future));
            log.info("Request queued for user: {}, queue size: {}", userId, queue.size());
            return future;
        }

        // 异步处理请求
        cp.busy = true;
        cp.lastActiveTime = System.currentTimeMillis();

        CompletableFuture.runAsync(() -> {
            try {
                String response = processMessage(cp, userId, message);
                future.complete(response);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                cp.busy = false;
                // processMessage 内部已调用 processEnded，这里不再重复
                processNextInQueue(userId);
            }
        });

        return future;
    }

    /**
     * 发送消息并返回结果 Future 和处理该消息的进程序号
     * @return SendResult 包含 future 和 processIndex（1-based，排队时为 -1）
     */
    public SendResult sendMessageWithIndex(String userId, String message) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (!quotaManager.canSendToolMessage(userId)) {
            future.complete("消息次数已达上限（10条），请等待当前任务完成");
            return new SendResult(future, -1);
        }

        ClaudeProcess cp = getOrCreateProcess(userId);
        if (cp == null) {
            BlockingQueue<PendingRequest> queue = requestQueues.computeIfAbsent(userId,
                    k -> new LinkedBlockingQueue<>());
            queue.offer(new PendingRequest(message, future));
            log.info("Request queued for user: {}, queue size: {}", userId, queue.size());
            return new SendResult(future, -1);
        }

        int processIndex = getProcessIndex(userId, cp);

        cp.busy = true;
        cp.lastActiveTime = System.currentTimeMillis();

        CompletableFuture.runAsync(() -> {
            try {
                String response = processMessage(cp, userId, message);
                future.complete(response);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                cp.busy = false;
                processNextInQueue(userId);
            }
        });

        return new SendResult(future, processIndex);
    }

    public record SendResult(CompletableFuture<String> future, int processIndex) {}

    private String processMessage(ClaudeProcess cp, String userId, String message) {
        long startTime = System.currentTimeMillis();
        int processIndex = getProcessIndex(userId, cp);

        try {
            // 检查 CLI 进程是否还活着
            if (!cp.hasActiveCliProcess()) {
                log.error("Claude CLI process is not alive for user: {}", userId);
                return "Claude CLI 进程已死亡";
            }

            // 如果没有 session（新会话），检查是否有记忆文档可恢复
            String effectiveMessage = message;
            if (cp.sessionId == null) {
                String workDir = cp.workDir;
                if (workDir == null || workDir.isEmpty()) {
                    workDir = claudeConfig.getWorkDir();
                }
                if (workDir == null || workDir.isEmpty()) {
                    workDir = System.getProperty("user.dir");
                }
                String memoryContent = getLatestMemoryDocument(workDir);
                if (memoryContent != null && !memoryContent.isEmpty()) {
                    effectiveMessage = "[系统上下文恢复] 以下是之前会话的压缩记忆文档，请基于此上下文继续工作：\n\n" + memoryContent + "\n\n---\n\n[用户消息] " + message;
                    log.info("Prepended memory document to message for user: {}", userId);
                }
            }

            // 通过 stdin 发送消息（text 格式，纯文本）
            log.info("Sending message to Claude stdin for user {}: {}", userId, effectiveMessage.length() > 200 ? effectiveMessage.substring(0, 200) + "..." : effectiveMessage);
            cp.stdin.write((effectiveMessage + "\n").getBytes());
            cp.stdin.flush();
            // 关闭 stdin 通知 Claude 输入结束，开始处理
            cp.stdin.close();
            log.info("Message flushed and stdin closed for user: {}", userId);

            // 后台收集 stderr 输出（用于错误诊断）
            StringBuilder stderrOutput = new StringBuilder();
            Thread stderrCollector = new Thread(() -> {
                try {
                    String line;
                    while ((line = cp.stderr.readLine()) != null) {
                        stderrOutput.append(line).append("\n");
                        log.info("Claude stderr for user {}: {}", userId, line);
                    }
                } catch (Exception e) {
                    log.debug("Claude stderr reader error for user {}: {}", userId, e.getMessage());
                }
            });
            stderrCollector.setDaemon(true);
            stderrCollector.start();

            // 读取响应
            StringBuilder output = new StringBuilder();
            String line;
            log.info("Waiting for Claude response on stdout for user: {}", userId);
            int lineCount = 0;
            while ((line = cp.stdout.readLine()) != null) {
                lineCount++;
                if (line.isEmpty()) continue;
                log.info("Claude stdout line {} for user {}: {}", lineCount, userId, line.length() > 200 ? line.substring(0, 200) + "..." : line);

                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(line);
                    String type = node.get("type").asText();

                    if ("assistant".equals(type)) {
                        com.fasterxml.jackson.databind.JsonNode messageNode = node.get("message");
                        if (messageNode != null) {
                            com.fasterxml.jackson.databind.JsonNode contentNode = messageNode.get("content");
                            if (contentNode != null && contentNode.isArray()) {
                                for (com.fasterxml.jackson.databind.JsonNode item : contentNode) {
                                    String itemType = item.get("type").asText();
                                    if ("text".equals(itemType)) {
                                        String text = item.get("text").asText();
                                        output.append(text);
                                        // 发送决策消息（不受配额限制）
                                        if (toolCallback != null && !text.isEmpty()) {
                                            toolCallback.onDecisionMessage(userId, text);
                                        }
                                    } else if ("tool_use".equals(itemType)) {
                                        String toolName = item.get("name").asText();
                                        String toolInput = item.get("input").toString();
                                        if (toolCallback != null) {
                                            toolCallback.onToolUse(userId, toolName, toolInput, processIndex);
                                            if (isSubtaskTool(toolName)) {
                                                String subtaskStatus = extractSubtaskStatus(toolName, toolInput);
                                                if (subtaskStatus != null) {
                                                    boolean isCompleted = isSubtaskCompleted(toolName, toolInput);
                                                    toolCallback.onSubtaskStatus(userId, subtaskStatus, isCompleted, processIndex);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if ("error".equals(type)) {
                        // 捕获 Claude API 错误（额度用尽、过载等）
                        String errorMsg = "";
                        if (node.has("error")) {
                            com.fasterxml.jackson.databind.JsonNode errorNode = node.get("error");
                            if (errorNode.has("message")) {
                                errorMsg = errorNode.get("message").asText();
                            } else if (errorNode.has("type")) {
                                errorMsg = errorNode.get("type").asText();
                            } else {
                                errorMsg = errorNode.toString();
                            }
                        } else if (node.has("message")) {
                            errorMsg = node.get("message").asText();
                        }
                        if (errorMsg.isEmpty()) {
                            errorMsg = node.toString();
                        }
                        output.append("⚠️ Claude API 错误: ").append(errorMsg).append("\n");
                        log.error("Claude API error for user {}: {}", userId, errorMsg);
                    } else if ("result".equals(type)) {
                        // 保存 session_id
                        com.fasterxml.jackson.databind.JsonNode sessionNode = node.get("session_id");
                        if (sessionNode != null) {
                            cp.sessionId = sessionNode.asText();
                            sessionIds.put(userId, sessionNode.asText());
                            sessionHistory.computeIfAbsent(userId, k -> new ArrayList<>());
                            if (!sessionHistory.get(userId).contains(sessionNode.asText())) {
                                sessionHistory.get(userId).add(sessionNode.asText());
                            }

                            // 父进程 session 变更时同步到子进程
                            if (cp.isParent) {
                                syncSessionToChildren(userId, sessionNode.asText());
                            }
                        }

                        // result 事件也可能包含 content（兼容新版本 Claude CLI）
                        com.fasterxml.jackson.databind.JsonNode resultContent = node.get("content");
                        if (resultContent != null && resultContent.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode item : resultContent) {
                                if (item.has("type") && "text".equals(item.get("type").asText())) {
                                    String text = item.get("text").asText();
                                    output.append(text);
                                }
                            }
                        }

                        // 解析 token 使用量（按 sessionId 统计，确保切换 session 后数据独立）
                        com.fasterxml.jackson.databind.JsonNode usage = node.get("usage");
                        if (usage != null) {
                            int inputTokens = usage.get("input_tokens").asInt();
                            int outputTokens = usage.get("output_tokens").asInt();
                            String currentSessionId = cp.sessionId != null ? cp.sessionId : sessionIds.get(userId);
                            if (currentSessionId != null) {
                                TokenUsage tokenUsage = tokenUsageMap.computeIfAbsent(currentSessionId, k -> new TokenUsage());
                                tokenUsage.add(inputTokens, outputTokens);
                            }
                        }

                        // 收到 result，退出循环
                        break;
                    }
                } catch (Exception e) {
                    // 非 JSON 行，可能是纯文本输出
                    output.append(line).append("\n");
                }
            }

            // 等待 stderr 收集线程完成
            stderrCollector.join(3000);

            long duration = System.currentTimeMillis() - startTime;

            String result = output.toString().trim();
            log.info("Claude process responded in {}ms for user: {}", duration, userId);

            // 关闭 stdin 后 CLI 进程会退出，销毁 CLI 进程但保留 Worker
            cp.destroyCliProcess();
            quotaManager.processEnded(userId);

            // 检查 stderr 是否有错误信息
            String stderr = stderrOutput.toString().trim();
            boolean hasStderrError = !stderr.isEmpty() && (
                    stderr.contains("error") || stderr.contains("Error") ||
                    stderr.contains("ERROR") || stderr.contains("quota") ||
                    stderr.contains("limit") || stderr.contains("overloaded") ||
                    stderr.contains("rate_limit") || stderr.contains("authentication"));

            if (result.isEmpty()) {
                if (!stderr.isEmpty()) {
                    return "Claude 未返回结果\n\n错误信息:\n" + stderr;
                }
                return "Claude 未返回结果";
            }

            // 如果有 stderr 错误信息，附加到结果中
            if (hasStderrError) {
                result = result + "\n\n⚠️ 错误信息:\n" + stderr;
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to process message for user: {}", userId, e);
            // CLI 进程可能已死亡，销毁但保留 Worker
            cp.destroyCliProcess();
            quotaManager.processEnded(userId);
            return "Claude 执行异常: " + e.getMessage();
        }
    }

    private void processNextInQueue(String userId) {
        BlockingQueue<PendingRequest> queue = requestQueues.get(userId);
        if (queue == null || queue.isEmpty()) return;

        PendingRequest next = queue.poll();
        if (next != null) {
            CompletableFuture<String> resultFuture = sendMessageAsync(userId, next.message);
            resultFuture.whenComplete((result, ex) -> {
                if (ex != null) {
                    next.future.completeExceptionally(ex);
                } else {
                    next.future.complete(result);
                }
            });

            // 如果请求被重新入队（sendMessageAsync 返回未完成的 future 且请求在队列中），
            // 安排延迟重试，避免请求永远卡在队列中
            if (!resultFuture.isDone()) {
                BlockingQueue<PendingRequest> currentQueue = requestQueues.get(userId);
                if (currentQueue != null && !currentQueue.isEmpty()) {
                    log.info("Request re-queued for user: {}, scheduling retry in 3s", userId);
                    cleanupScheduler.schedule(() -> processNextInQueue(userId), 3, TimeUnit.SECONDS);
                }
            }
        }
    }

    /**
     * 取消所有待处理的排队请求，完成其 future 避免 handleMessage 永久阻塞
     */
    private void cancelPendingRequests(String userId) {
        BlockingQueue<PendingRequest> queue = requestQueues.remove(userId);
        if (queue != null) {
            PendingRequest pending;
            while ((pending = queue.poll()) != null) {
                pending.future.complete("⚠️ 进程已销毁，排队任务已取消");
                log.info("Cancelled pending request for user: {}", userId);
            }
        }
    }

    /**
     * 父进程 session 变更时同步到子进程
     * 子进程在当前请求完成后，下次 fork 时会使用新 session
     */
    private void syncSessionToChildren(String userId, String newSessionId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return;

        for (ClaudeProcess p : pool) {
            if (!p.isParent && userId.equals(p.parentId)) {
                p.sessionId = newSessionId;
                log.info("Synced new session {} to fork child for user: {}", newSessionId, userId);
            }
        }
    }

    // ==================== 进程管理命令 ====================

    public void destroyProcess(String userId) {
        List<ClaudeProcess> pool = processPools.remove(userId);
        if (pool != null) {
            pool.forEach(ClaudeProcess::destroy);
        }
        cancelPendingRequests(userId);
    }

    /**
     * 销毁进程组（父进程 + 所有子进程）
     */
    public void destroyProcessGroup(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool != null) {
            // 先销毁所有子进程
            pool.removeIf(p -> !p.isParent && userId.equals(p.parentId));
            // 再销毁父进程
            pool.removeIf(p -> p.isParent);
            // 清空剩余进程
            pool.forEach(ClaudeProcess::destroy);
        }
        processPools.remove(userId);
        cancelPendingRequests(userId);
    }

    public void destroyAllProcesses(String userId) {
        destroyProcess(userId);
    }

    public int getProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        return pool != null ? pool.size() : 0;
    }

    public boolean hasBusyProcesses(String userId) {
        return getBusyProcessCount(userId) > 0;
    }

    /**
     * 检查是否有空闲 worker 可立即处理消息
     */
    public boolean hasIdleProcess(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return false;

        for (ClaudeProcess p : pool) {
            if (!p.busy) {
                return true;
            }
        }

        // 如果池为空或全忙，但未达上限，可以创建新 worker
        return pool.size() < claudeConfig.getMaxProcessesPerUser();
    }

    public void forceStopAll(String userId) {
        destroyProcessGroup(userId);
    }

    /**
     * 停止当前进程（第一个父进程）的任务
     */
    public String forceStopCurrentProcess(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        // 找到父 Worker
        ClaudeProcess parent = null;
        for (ClaudeProcess p : pool) {
            if (p.isParent) {
                parent = p;
                break;
            }
        }

        if (parent == null) {
            return "无活跃的父进程";
        }

        if (!parent.busy) {
            return "当前进程没有正在运行的任务";
        }

        parent.destroy();
        pool.remove(parent);
        quotaManager.processEnded(userId);

        return "已停止当前父进程的任务";
    }

    /**
     * 停止指定进程的任务
     */
    public String forceStopProcess(String userId, int index) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        if (index < 0 || index >= pool.size()) {
            return "无效序号，范围: 1-" + pool.size();
        }

        ClaudeProcess target = pool.get(index);

        if (!target.busy) {
            return "进程" + (index + 1) + "没有正在运行的任务";
        }

        target.destroy();
        pool.remove(target);
        quotaManager.processEnded(userId);

        String type = target.isParent ? "父进程" : "子进程";
        return "已停止" + type + " (序号: " + (index + 1) + ")";
    }

    public int getBusyProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (p.busy) count++;
        }
        return count;
    }

    /**
     * 获取当前进程组的忙碌进程数（排除独立进程）
     */
    public int getGroupBusyProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (p.busy && !p.isIndependent) count++;
        }
        return count;
    }

    /**
     * 获取当前进程组的空闲进程数（排除独立进程）
     * 普通消息只会分配给进程组内的 worker，独立进程不会被使用
     */
    public int getGroupIdleProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (!p.busy && !p.isIndependent) count++;
        }
        return count;
    }

    /**
     * 获取进程在池中的序号（1-based），找不到返回 -1
     */
    public int getProcessIndex(String userId, ClaudeProcess target) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || target == null) return -1;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i) == target) return i + 1;
        }
        return -1;
    }

    /**
     * 获取当前进程组的进程数（排除独立进程）
     */
    public int getGroupProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (!p.isIndependent) count++;
        }
        return count;
    }

    public int getIndependentProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (p.isIndependent) count++;
        }
        return count;
    }

    public int getForkChildCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (!p.isParent && userId.equals(p.parentId)) {
                count++;
            }
        }
        return count;
    }

    public String getProcessStatus(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) return "无活跃进程";

        // 找到父进程的序号
        int parentIndex = -1;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).isParent) {
                parentIndex = i + 1;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("| # | 类型 | 来源 | 会话ID | 工作目录 | 状态 |\n");
        sb.append("|---|------|------|--------|----------|------|\n");

        for (int i = 0; i < pool.size(); i++) {
            ClaudeProcess p = pool.get(i);
            String type = p.isParent ? "父进程" : "子进程";
            String source = p.isParent ? "-" : "克隆自父进程#" + parentIndex;
            String sessionId = p.sessionId != null ? p.sessionId.substring(0, Math.min(12, p.sessionId.length())) + "..." : "无";
            String workDir = p.workDir != null ? p.workDir : "默认";
            String status = p.busy ? "忙碌" : "空闲";

            sb.append(String.format("| %d | %s | %s | `%s` | `%s` | %s |\n",
                    i + 1,
                    type,
                    source,
                    sessionId,
                    workDir,
                    status
            ));
        }
        return sb.toString();
    }

    /**
     * 切换到指定序号的进程（同时切换会话和工作目录）
     */
    public String switchProcess(String userId, int index) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        if (index < 0 || index >= pool.size()) {
            return "无效序号，范围: 1-" + pool.size();
        }

        ClaudeProcess target = pool.get(index);

        // 记录当前进程索引
        currentProcessIndex.put(userId, index);

        // 切换会话
        if (target.sessionId != null) {
            sessionIds.put(userId, target.sessionId);
            syncSessionToChildren(userId, target.sessionId);
        }

        String type = target.isParent ? "父进程" : "子进程";
        String busyHint = target.busy ? " [忙碌中]" : "";
        return String.format("已切换到进程%d (%s)%s\n\n📋 会话ID: `%s`\n📁 工作目录: `%s`",
                index + 1,
                type,
                busyHint,
                target.sessionId != null ? target.sessionId.substring(0, Math.min(12, target.sessionId.length())) + "..." : "无",
                target.workDir != null ? target.workDir : "默认"
        );
    }

    /**
     * 克隆当前进程
     * 如果当前进程是子进程，则克隆到父进程下（不传递子进程）
     */
    public String cloneCurrentProcess(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);

        // 检查进程数上限
        int currentCount = pool != null ? pool.size() : 0;
        if (currentCount >= claudeConfig.getMaxProcessesPerUser()) {
            return "进程数已达上限 (" + currentCount + "/" + claudeConfig.getMaxProcessesPerUser() + ")";
        }

        // 检查是否有进程可以克隆
        if (pool == null || pool.isEmpty()) {
            return "无进程可克隆，请先发送消息创建父进程";
        }

        // 获取当前活跃进程索引，默认为 0（父进程）
        int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
        if (activeIndex < 0 || activeIndex >= pool.size()) {
            activeIndex = 0;
        }

        ClaudeProcess activeProcess = pool.get(activeIndex);

        // 克隆当前活跃进程
        ClaudeProcess cp;
        if (activeProcess.isIndependent) {
            // 当前是独立进程，直接 fork 自己的 session 和工作目录
            cp = forkProcess(userId, pool, activeProcess.sessionId, activeProcess.workDir);
        } else if (activeProcess.isParent) {
            // 当前是父进程，fork 共享父进程的 session 和工作目录
            cp = forkProcess(userId, pool);
        } else {
            // 当前是子进程，fork 共享当前子进程的 session 和工作目录
            cp = forkProcess(userId, pool, activeProcess.sessionId, activeProcess.workDir);
        }

        if (cp == null) {
            return "克隆进程失败";
        }

        // 尝试处理排队的消息
        processNextInQueue(userId);

        return String.format("✅ 已克隆进程\n\n📋 会话ID: `%s`\n📁 工作目录: `%s`",
                cp.sessionId != null ? cp.sessionId.substring(0, Math.min(12, cp.sessionId.length())) + "..." : "无",
                cp.workDir != null ? cp.workDir : "默认"
        );
    }

    /**
     * 创建新独立进程（非克隆），可选绑定指定 session
     * 新进程不在任何进程组，不共享任务
     */
    public String createNewProcess(String userId, String sessionId) {
        return createNewProcess(userId, sessionId, null);
    }

    /**
     * 创建新独立进程（非克隆），可选绑定指定 session 和工作目录
     * 新进程不在任何进程组，不共享任务
     * @param sessionId 会话ID，null 表示不绑定会话
     * @param workDir 工作目录，null 表示使用默认目录
     */
    public String createNewProcess(String userId, String sessionId, String workDir) {
        List<ClaudeProcess> pool = processPools.get(userId);

        // 检查进程数上限
        int currentCount = pool != null ? pool.size() : 0;
        if (currentCount >= claudeConfig.getMaxProcessesPerUser()) {
            return "进程数已达上限 (" + currentCount + "/" + claudeConfig.getMaxProcessesPerUser() + ")";
        }

        // 如果指定了 session，验证是否有效
        if (sessionId != null) {
            List<String> history = sessionHistory.get(userId);
            if (history == null || !history.contains(sessionId)) {
                return "未找到会话: " + sessionId;
            }
            // 临时设置 session 用于创建进程
            sessionIds.put(userId, sessionId);
        }

        // 获取或创建进程池
        if (pool == null) {
            pool = processPools.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        }

        // 创建新独立 Worker（不是克隆，标记为独立进程）
        ClaudeProcess cp = createNewWorker(userId, pool, true, true);
        if (cp == null) {
            return "创建新进程失败";
        }

        // 如果指定了工作目录，覆盖默认值
        if (workDir != null) {
            cp.workDir = workDir;
        }

        // 如果未指定 session，清除 worker 继承的 sessionId（使用路径参数时不绑定会话）
        if (sessionId == null) {
            cp.sessionId = null;
        }

        // 尝试处理排队的消息
        processNextInQueue(userId);

        String sessionDisplay = cp.sessionId != null ? cp.sessionId.substring(0, Math.min(12, cp.sessionId.length())) + "..." : "首次使用时自动创建";
        return String.format("✅ 已创建新进程\n\n📋 会话: `%s`\n📁 工作目录: `%s`",
                sessionDisplay,
                cp.workDir != null ? cp.workDir : "默认"
        );
    }

    /**
     * 删除指定进程
     * 如果该进程有子进程（克隆进程），则不可删除，必须先删除子进程
     */
    public String deleteProcess(String userId, int index) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        if (index < 0 || index >= pool.size()) {
            return "无效序号，范围: 1-" + pool.size();
        }

        ClaudeProcess target = pool.get(index);

        // 检查是否有子进程
        if (target.isParent) {
            // 父进程：检查是否有子进程
            for (ClaudeProcess p : pool) {
                if (!p.isParent && userId.equals(p.parentId)) {
                    return "该父进程有子进程，无法删除\n请先使用 v-delproc 删除子进程";
                }
            }
        }

        // 检查进程是否忙碌
        if (target.busy) {
            return "进程" + (index + 1) + "正在忙碌中，无法删除";
        }

        // 销毁进程
        target.destroy();
        pool.remove(target);
        quotaManager.processEnded(userId);

        // 如果池空了，移除
        if (pool.isEmpty()) {
            processPools.remove(userId);
        }

        String type = target.isParent ? "父进程" : "子进程";
        return "已删除" + type + " (序号: " + (index + 1) + ")";
    }

    // ==================== 空闲进程清理 ====================

    private void cleanupIdleProcesses() {
        long now = System.currentTimeMillis();
        long timeout = claudeConfig.getProcessIdleTimeoutMs();

        // 收集需要移除的空 userId
        java.util.List<String> emptyPools = new ArrayList<>();

        processPools.forEach((userId, pool) -> {
            // 先清理空闲超时的子进程（fork child）
            pool.removeIf(p -> {
                if (!p.isParent && !p.busy && (now - p.lastActiveTime) > timeout) {
                    log.info("Cleaning up idle fork child process for user: {}", userId);
                    p.destroy();
                    quotaManager.processEnded(userId);
                    return true;
                }
                return false;
            });

            // 清理空闲超时的父进程（仅在没有子进程时）
            boolean hasChildren = pool.stream().anyMatch(p -> !p.isParent && userId.equals(p.parentId));
            if (!hasChildren) {
                pool.removeIf(p -> {
                    if (p.isParent && !p.busy && (now - p.lastActiveTime) > timeout) {
                        log.info("Cleaning up idle parent process for user: {}", userId);
                        p.destroy();
                        quotaManager.processEnded(userId);
                        return true;
                    }
                    return false;
                });
            }

            // 数量超限时，清理最早的空闲子进程
            int maxProcesses = claudeConfig.getMaxProcessesPerUser();
            while (pool.size() > maxProcesses) {
                // 找到最早的空闲子进程
                ClaudeProcess oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (ClaudeProcess p : pool) {
                    if (!p.isParent && !p.busy && p.lastActiveTime < oldestTime) {
                        oldest = p;
                        oldestTime = p.lastActiveTime;
                    }
                }
                if (oldest != null) {
                    log.info("Cleaning up excess fork child process for user: {}, pool size: {}", userId, pool.size());
                    oldest.destroy();
                    quotaManager.processEnded(userId);
                    pool.remove(oldest);
                } else {
                    break; // 没有空闲进程可清理
                }
            }

            // 如果池空了，标记移除
            if (pool.isEmpty()) {
                emptyPools.add(userId);
            }
        });

        // 统一移除空池
        for (String userId : emptyPools) {
            processPools.remove(userId);
        }
    }

    // ==================== 工具调用回调 ====================

    public interface ToolCallback {
        void onToolUse(String userId, String toolName, String toolInput, int processIndex);
        void onToolResult(String userId, String result);
        void onSubtaskStatus(String userId, String status, boolean isCompleted, int processIndex);
        void onDecisionMessage(String userId, String message);
    }

    private ToolCallback toolCallback;

    public void setToolCallback(ToolCallback callback) {
        this.toolCallback = callback;
    }

    private boolean isSubtaskTool(String toolName) {
        return "TaskCreate".equals(toolName) || "TaskUpdate".equals(toolName)
                || "TaskGet".equals(toolName) || "TaskList".equals(toolName);
    }

    private String extractSubtaskStatus(String toolName, String toolInput) {
        try {
            com.fasterxml.jackson.databind.JsonNode inputNode = objectMapper.readTree(toolInput);
            return switch (toolName) {
                case "TaskCreate" -> {
                    String subject = inputNode.has("subject") ? inputNode.get("subject").asText() : "";
                    String taskId = inputNode.has("taskId") ? inputNode.get("taskId").asText() : null;
                    // taskId 为 null 时（正常情况），用 subject 本身作为后备 key
                    if (!subject.isEmpty()) {
                        subtaskNames.put(subject, subject);
                    }
                    if (taskId != null && !subject.isEmpty()) {
                        subtaskNames.put(taskId, subject);
                    }
                    yield "📋 创建子任务: " + subject;
                }
                case "TaskUpdate" -> {
                    String status = inputNode.has("status") ? inputNode.get("status").asText() : "更新";
                    String taskId = inputNode.has("taskId") ? inputNode.get("taskId").asText() : "";
                    // 从缓存获取 subject：先用 taskId 查，再用 subject 本身查
                    String subject = !taskId.isEmpty() ? subtaskNames.getOrDefault(taskId, "") : "";
                    if (subject.isEmpty() && inputNode.has("subject")) {
                        String inputSubject = inputNode.get("subject").asText();
                        subject = subtaskNames.getOrDefault(inputSubject, inputSubject);
                    }
                    if ("completed".equals(status)) {
                        yield "✅ 子任务完成" + (subject.isEmpty() ? "" : ": " + subject);
                    }
                    yield "🔄 子任务 " + status + (subject.isEmpty() ? "" : ": " + subject);
                }
                case "TaskGet" -> "📖 查看子任务: " + (inputNode.has("taskId") ? inputNode.get("taskId").asText() : "");
                case "TaskList" -> "📋 列出所有子任务";
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSubtaskCompleted(String toolName, String toolInput) {
        try {
            if ("TaskUpdate".equals(toolName)) {
                com.fasterxml.jackson.databind.JsonNode inputNode = objectMapper.readTree(toolInput);
                return inputNode.has("status") && "completed".equals(inputNode.get("status").asText());
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    // ==================== 路径检测和配置加载 ====================

    private void detectClaudePath() {
        String[] commonPaths = {
            "/usr/bin/claude",
            "/usr/local/bin/claude",
            "/opt/claude/bin/claude",
            System.getProperty("user.home") + "/claude/bin/claude",
            System.getProperty("user.home") + "/.local/bin/claude",
            System.getProperty("user.home") + "/.claude/bin/claude"
        };

        for (String path : commonPaths) {
            if (Files.exists(Path.of(path))) {
                claudeConfig.setInstallPath(path);
                log.info("Detected Claude installation at: {}", path);
                loadClaudeConfig();
                return;
            }
        }

        try {
            Process process = new ProcessBuilder("which", "claude").start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (!output.isEmpty() && Files.exists(Path.of(output))) {
                claudeConfig.setInstallPath(output);
                log.info("Detected Claude installation at: {}", output);
                loadClaudeConfig();
                return;
            }
        } catch (Exception e) {
            log.debug("Failed to find Claude via which command");
        }

        log.warn("Claude installation not found");
    }

    private void loadClaudeConfig() {
        try {
            String configPath = System.getProperty("user.home") + "/.claude/settings.json";
            Path configFilePath = Path.of(configPath);

            if (Files.exists(configFilePath)) {
                String content = new String(Files.readAllBytes(configFilePath));

                int baseUrlStart = content.indexOf("\"ANTHROPIC_BASE_URL\":\"") + 22;
                int baseUrlEnd = content.indexOf("\"", baseUrlStart);
                if (baseUrlStart > 21 && baseUrlEnd > baseUrlStart) {
                    String baseUrl = content.substring(baseUrlStart, baseUrlEnd);
                    if (claudeConfig.getApiUrl() == null || claudeConfig.getApiUrl().isEmpty()) {
                        claudeConfig.setApiUrl(baseUrl);
                        log.info("Loaded Claude API URL: {}", baseUrl);
                    }
                }

                int tokenStart = content.indexOf("\"ANTHROPIC_AUTH_TOKEN\":\"") + 23;
                int tokenEnd = content.indexOf("\"", tokenStart);
                if (tokenStart > 22 && tokenEnd > tokenStart) {
                    String token = content.substring(tokenStart, tokenEnd);
                    if (claudeConfig.getApiKey() == null || claudeConfig.getApiKey().isEmpty()) {
                        claudeConfig.setApiKey(token);
                        log.info("Loaded Claude API Key: {}...{}", token.substring(0, Math.min(8, token.length())), token.substring(Math.max(0, token.length() - 4)));
                    }
                }

                int modelStart = content.indexOf("\"ANTHROPIC_MODEL\":\"") + 20;
                int modelEnd = content.indexOf("\"", modelStart);
                if (modelStart > 19 && modelEnd > modelStart) {
                    String model = content.substring(modelStart, modelEnd);
                    if (claudeConfig.getModel() == null || claudeConfig.getModel().isEmpty()) {
                        claudeConfig.setModel(model);
                        log.info("Loaded Claude Model: {}", model);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Claude config: {}", e.getMessage());
        }
    }

    // ==================== Token 用量 ====================

    public static class TokenUsage {
        private int inputTokens = 0;
        private int outputTokens = 0;
        private int totalTokens = 0;

        public synchronized void add(int input, int output) {
            this.inputTokens += input;
            this.outputTokens += output;
            this.totalTokens = inputTokens + outputTokens;
        }

        public synchronized int getInputTokens() { return inputTokens; }
        public synchronized int getOutputTokens() { return outputTokens; }
        public synchronized int getTotalTokens() { return totalTokens; }
    }

    public void clearHistory(String userId) {
        // 清除该用户所有 session 的 token 用量
        List<String> sessions = sessionHistory.get(userId);
        if (sessions != null) {
            sessions.forEach(tokenUsageMap::remove);
            sessions.forEach(memoryDocuments::remove); // 清理记忆文档绑定
        }
        String currentSession = sessionIds.get(userId);
        if (currentSession != null) {
            tokenUsageMap.remove(currentSession);
            memoryDocuments.remove(currentSession); // 清理记忆文档绑定
        }
        sessionIds.remove(userId);
        sessionHistory.remove(userId);
        quotaManager.reset(userId);
        destroyProcessGroup(userId);
    }

    /**
     * 仅清除当前 session，保留其他 session
     */
    public void clearCurrentSession(String userId) {
        String currentSession = sessionIds.get(userId);
        if (currentSession != null) {
            tokenUsageMap.remove(currentSession);
            memoryDocuments.remove(currentSession); // 清理记忆文档绑定
            List<String> history = sessionHistory.get(userId);
            if (history != null) {
                history.remove(currentSession);
            }
        }
        sessionIds.remove(userId);
        quotaManager.reset(userId);

        // 同步清除进程池中所有 worker 的 sessionId
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool != null) {
            for (ClaudeProcess p : pool) {
                p.sessionId = null;
                p.destroyCliProcess(); // 销毁 CLI 进程，下次使用时重建
            }
        }
    }

    /**
     * 清除所有 session，但保留进程
     */
    public void clearAllSessions(String userId) {
        List<String> sessions = sessionHistory.get(userId);
        if (sessions != null) {
            sessions.forEach(tokenUsageMap::remove);
            sessions.forEach(memoryDocuments::remove); // 清理记忆文档绑定
        }
        String currentSession = sessionIds.get(userId);
        if (currentSession != null) {
            tokenUsageMap.remove(currentSession);
            memoryDocuments.remove(currentSession); // 清理记忆文档绑定
        }
        sessionIds.remove(userId);
        sessionHistory.remove(userId);
        quotaManager.reset(userId);
    }

    public String getSessionId(String userId) {
        return sessionIds.get(userId);
    }

    /**
     * 清除用户的当前 sessionId（切换 API/Key/工作区后调用，避免使用旧 session）
     */
    public void clearSessionId(String userId) {
        sessionIds.remove(userId);
    }

    public List<String> getSessionHistory(String userId) {
        return sessionHistory.getOrDefault(userId, new ArrayList<>());
    }

    public boolean switchSession(String userId, String sessionId) {
        List<String> history = sessionHistory.get(userId);
        if (history != null && history.contains(sessionId)) {
            sessionIds.put(userId, sessionId);
            // 同步到子进程
            syncSessionToChildren(userId, sessionId);
            return true;
        }
        return false;
    }

    public boolean deleteSession(String userId, String sessionId) {
        List<String> history = sessionHistory.get(userId);
        if (history != null && history.remove(sessionId)) {
            if (sessionId.equals(sessionIds.get(userId))) {
                sessionIds.remove(userId);
                destroyProcessGroup(userId);
            }
            // 清理记忆文档绑定
            memoryDocuments.remove(sessionId);
            return true;
        }
        return false;
    }

    // ==================== 记忆文档管理 ====================

    /**
     * 保存上下文压缩记忆文档
     * @param sessionId 会话ID
     * @param workDir 工作目录
     * @param content 文档内容
     * @return 文件路径，失败返回 null
     */
    public String saveMemoryDocument(String sessionId, String workDir, String content) {
        try {
            Path memoryDir = Path.of(workDir, ".vibe-memory");
            if (!Files.exists(memoryDir)) {
                Files.createDirectories(memoryDir);
            }

            String fileName = "context-" + sessionId.substring(0, Math.min(12, sessionId.length())) + ".md";
            Path filePath = memoryDir.resolve(fileName);

            String documentContent = String.format("""
                    # 上下文压缩文档

                    **会话ID**: %s
                    **工作目录**: %s
                    **生成时间**: %s

                    ---

                    %s
                    """,
                    sessionId,
                    workDir,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    content
            );

            Files.write(filePath, documentContent.getBytes());
            memoryDocuments.put(sessionId, filePath.toString());
            log.info("Saved memory document for session {}: {}", sessionId, filePath);
            return filePath.toString();
        } catch (Exception e) {
            log.error("Failed to save memory document for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * 获取指定工作目录的最新记忆文档内容
     * @param workDir 工作目录
     * @return 文档内容，不存在返回 null
     */
    public String getLatestMemoryDocument(String workDir) {
        try {
            Path memoryDir = Path.of(workDir, ".vibe-memory");
            if (!Files.exists(memoryDir)) {
                return null;
            }

            // 查找最新的记忆文档
            Path latestFile = null;
            long latestTime = 0;

            try (var stream = Files.list(memoryDir)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (file.toString().endsWith(".md")) {
                        long lastModified = Files.getLastModifiedTime(file).toMillis();
                        if (lastModified > latestTime) {
                            latestTime = lastModified;
                            latestFile = file;
                        }
                    }
                }
            }

            if (latestFile != null) {
                String content = new String(Files.readAllBytes(latestFile));
                log.info("Loaded memory document from: {}", latestFile);
                return content;
            }
        } catch (Exception e) {
            log.error("Failed to load memory document from {}", workDir, e);
        }
        return null;
    }

    /**
     * 删除指定会话的记忆文档
     * @param sessionId 会话ID
     */
    public void deleteMemoryDocument(String sessionId) {
        String filePath = memoryDocuments.remove(sessionId);
        if (filePath != null) {
            try {
                Files.deleteIfExists(Path.of(filePath));
                log.info("Deleted memory document for session {}: {}", sessionId, filePath);
            } catch (Exception e) {
                log.error("Failed to delete memory document for session {}", sessionId, e);
            }
        }
    }

    /**
     * 删除指定工作目录下的所有记忆文档
     * @param workDir 工作目录
     */
    public void deleteAllMemoryDocuments(String workDir) {
        try {
            Path memoryDir = Path.of(workDir, ".vibe-memory");
            if (Files.exists(memoryDir)) {
                try (var stream = Files.list(memoryDir)) {
                    for (Path file : (Iterable<Path>) stream::iterator) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(memoryDir);
                log.info("Deleted all memory documents from {}", workDir);
            }
        } catch (Exception e) {
            log.error("Failed to delete memory documents from {}", workDir, e);
        }
        // 清理绑定关系
        memoryDocuments.entrySet().removeIf(entry -> entry.getValue().startsWith(workDir));
    }

    public TokenUsage getTokenUsage(String userId) {
        String sessionId = sessionIds.get(userId);
        if (sessionId != null) {
            return tokenUsageMap.getOrDefault(sessionId, new TokenUsage());
        }
        return new TokenUsage();
    }

    public String getTokenUsageSummary(String userId) {
        TokenUsage usage = getTokenUsage(userId);
        String sessionId = sessionIds.get(userId);
        String sessionLabel = sessionId != null ? " (Session: " + sessionId.substring(0, Math.min(12, sessionId.length())) + "...)" : "";
        return String.format("输入: %s, 输出: %s, 总计: %s%s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), formatTokens(usage.totalTokens), sessionLabel);
    }

    public String getTaskCompletionSummary(String userId, long durationMs, String responseContent) {
        TokenUsage usage = getTokenUsage(userId);
        String duration = formatDuration(durationMs);
        String sessionId = sessionIds.get(userId);
        String sessionInfo = sessionId != null ? "\n📋 Session: `" + sessionId + "`" : "";
        int contextWindow = parseContextWindowSize(claudeConfig.getModel());
        int contextPercent = contextWindow > 0 ? Math.min(100, (int) (usage.inputTokens * 100.0 / contextWindow)) : 0;
        String contextInfo = "\n🧠 上下文: " + contextPercent + "% (" + formatTokens(usage.inputTokens) + "/" + formatTokens(contextWindow) + ")";

        // 自动压缩检查：超过阈值时生成记忆文档并清除会话
        String compactionInfo = "";
        if (contextPercent >= claudeConfig.getContextCompactionThreshold() && sessionId != null) {
            // 保存记忆文档
            String workDir = claudeConfig.getWorkDir();
            if (workDir == null || workDir.isEmpty()) {
                workDir = System.getProperty("user.dir");
            }
            String memoryPath = saveMemoryDocument(sessionId, workDir, responseContent);
            String memoryNote = memoryPath != null ? "\n📁 记忆文档已保存: `.vibe-memory/`" : "";

            // 清除当前会话，下次消息将使用新会话（自动压缩）
            sessionIds.remove(userId);
            destroyProcessGroup(userId);
            compactionInfo = "\n\n🔄 **上下文已自动压缩**（使用量 " + contextPercent + "% 超过阈值 " + claudeConfig.getContextCompactionThreshold() + "%）" + memoryNote + "\n下次消息将自动读取记忆文档恢复上下文";
            log.info("Auto compaction triggered for user: {}, context: {}%, memory: {}", userId, contextPercent, memoryPath);
        }

        return String.format("---\n📊 本次: %s in / %s out | ⏱️ %s%s%s%s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), duration, sessionInfo, contextInfo, compactionInfo);
    }

    private int parseContextWindowSize(String model) {
        if (model == null) return claudeConfig.getContextWindowSize();
        if (model.contains("[1m]") || model.contains("[1M]")) {
            return 1000000;
        } else if (model.contains("[200k]") || model.contains("[200K]")) {
            return 200000;
        }
        return claudeConfig.getContextWindowSize();
    }

    private String formatTokens(int tokens) {
        if (tokens >= 1000000) {
            return String.format("%.1fm", tokens / 1000000.0);
        } else if (tokens >= 1000) {
            return String.format("%.1fk", tokens / 1000.0);
        }
        return String.valueOf(tokens);
    }

    public String getTaskSummary(String userId, String originalMessage) {
        try {
            String summary = extractCoreIntent(originalMessage);
            return summary.isEmpty() ? "任务处理完成" : summary;
        } catch (Exception e) {
            return "任务处理完成";
        }
    }

    private String extractCoreIntent(String message) {
        String cleaned = message
                .replaceAll("^(请|帮我|帮忙|麻烦|能不能|可以|是否|是不是|有没有)\\s*", "")
                .replaceAll("(一下|吗|呢|吧|啊|哦|嗯|好的|谢谢|感谢)\\s*$", "")
                .trim();

        if (cleaned.length() <= 15) {
            return cleaned;
        }

        String[] sentenceEnders = {"。", "！", "？", ".", "!", "?"};
        int minEnd = cleaned.length();
        for (String ender : sentenceEnders) {
            int pos = cleaned.indexOf(ender);
            if (pos > 0 && pos < minEnd) {
                minEnd = pos;
            }
        }

        if (minEnd < cleaned.length()) {
            return cleaned.substring(0, minEnd + 1).trim();
        }

        return cleaned.length() > 20 ? cleaned.substring(0, 20) + "..." : cleaned;
    }

    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        long seconds = ms / 1000;
        long remainingMs = ms % 1000;
        if (seconds < 60) {
            return seconds + "s" + remainingMs + "ms";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + "m" + seconds + "s" + remainingMs + "ms";
    }

    // ==================== 磁盘会话管理 ====================

    /**
     * 扫描 ~/.claude/sessions/ 目录获取所有磁盘会话
     */
    public void scanDiskSessions() {
        diskSessions.clear();
        Path sessionsDir = Path.of(System.getProperty("user.home"), ".claude", "sessions");
        if (!Files.exists(sessionsDir)) {
            log.info("Claude sessions directory not found: {}", sessionsDir);
            return;
        }

        try {
            Files.list(sessionsDir)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        String content = new String(Files.readAllBytes(path));
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(content);

                        String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : null;
                        String pid = node.has("pid") ? node.get("pid").asText() : null;
                        String cwd = node.has("cwd") ? node.get("cwd").asText() : null;
                        long startedAt = node.has("startedAt") ? node.get("startedAt").asLong() : 0;
                        String status = node.has("status") ? node.get("status").asText() : "unknown";

                        if (sessionId != null) {
                            diskSessions.add(new DiskSession(sessionId, pid, cwd, startedAt, status));
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse session file: {}", path, e);
                    }
                });

            log.info("Found {} disk sessions", diskSessions.size());
        } catch (Exception e) {
            log.error("Failed to scan disk sessions", e);
        }
    }

    /**
     * 获取所有磁盘会话列表
     */
    public List<DiskSession> getDiskSessions() {
        scanDiskSessions();
        return diskSessions;
    }

    /**
     * 从磁盘恢复会话
     */
    public boolean resumeDiskSession(String userId, String sessionId) {
        // 检查会话是否存在
        boolean exists = diskSessions.stream().anyMatch(s -> s.sessionId.equals(sessionId));
        if (!exists) {
            // 重新扫描一次
            scanDiskSessions();
            exists = diskSessions.stream().anyMatch(s -> s.sessionId.equals(sessionId));
        }

        if (exists) {
            sessionIds.put(userId, sessionId);
            sessionHistory.computeIfAbsent(userId, k -> new ArrayList<>());
            if (!sessionHistory.get(userId).contains(sessionId)) {
                sessionHistory.get(userId).add(sessionId);
            }
            log.info("Resumed disk session {} for user: {}", sessionId, userId);
            return true;
        }
        return false;
    }

    /**
     * 清理孤儿进程（不属于当前服务的 Claude 进程）
     * 在服务启动时调用，清理异常关闭后遗留的子进程
     */
    public void cleanupOrphanProcesses() {
        log.info("Checking for orphan Claude processes...");

        try {
            // 获取当前 Java 进程的 PID
            long currentPid = ProcessHandle.current().pid();

            // 遍历所有 Java 子进程
            ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command().map(cmd -> cmd.contains("claude")).orElse(false))
                .forEach(ph -> {
                    // 检查父进程是否是当前 Java 进程
                    ph.parent().ifPresent(parent -> {
                        if (parent.pid() == currentPid) {
                            // 这是当前服务的子进程，不处理
                            return;
                        }

                        // 检查父进程是否还活着
                        if (!parent.isAlive()) {
                            // 父进程已死，这是孤儿进程
                            log.info("Found orphan Claude process: PID={}, killing...", ph.pid());
                            ph.destroyForcibly();
                        }
                    });

                    // 没有父进程的也是孤儿
                    if (ph.parent().isEmpty()) {
                        log.info("Found orphan Claude process (no parent): PID={}, killing...", ph.pid());
                        ph.destroyForcibly();
                    }
                });

        } catch (Exception e) {
            log.error("Failed to cleanup orphan processes", e);
        }
    }

    // ==================== Getters and Setters ====================

    public ClaudeConfig getClaudeConfig() { return claudeConfig; }

    public String getApiKey() { return claudeConfig.getApiKey(); }
    public void setApiKey(String key) { claudeConfig.setApiKey(key); }
    public String getApiUrl() { return claudeConfig.getApiUrl(); }
    public void setApiUrl(String url) { claudeConfig.setApiUrl(url); }
    public String getModel() { return claudeConfig.getModel(); }
    public void setModel(String model) { claudeConfig.setModel(model); }
    public String getInstallPath() { return claudeConfig.getInstallPath(); }
    public void setInstallPath(String path) { claudeConfig.setInstallPath(path); }
    public String getWorkDir() { return claudeConfig.getWorkDir(); }
    public void setWorkDir(String dir) {
        claudeConfig.setWorkDir(dir);
        // 同步更新所有已有 worker 的工作目录，避免使用旧目录
        processPools.forEach((userId, pool) -> {
            for (ClaudeProcess p : pool) {
                if (!p.busy) {
                    p.workDir = dir;
                    p.sessionId = null; // 切换目录后旧 session 不可用，清除
                    p.destroyCliProcess(); // 旧 CLI 进程需销毁，下次使用新目录重建
                }
            }
        });
        // 清除所有用户的 sessionId，避免 --resume 尝试恢复不存在的 session
        sessionIds.clear();
    }

    public int getContextWindowSize() { return claudeConfig.getContextWindowSize(); }
    public void setContextWindowSize(int size) { claudeConfig.setContextWindowSize(size); }

    // 进程配置
    public int getMaxProcessesPerUser() { return claudeConfig.getMaxProcessesPerUser(); }
    public void setMaxProcessesPerUser(int max) { claudeConfig.setMaxProcessesPerUser(max); }
    public long getProcessIdleTimeoutMs() { return claudeConfig.getProcessIdleTimeoutMs(); }
    public void setProcessIdleTimeoutMs(long timeout) { claudeConfig.setProcessIdleTimeoutMs(timeout); }

    public String getThinkingLevel() { return thinkingConfig.getLevel(); }
    public void setThinkingLevel(String level) { thinkingConfig.setLevel(level); }
    public boolean isThinkingEnabled() { return thinkingConfig.isEnabled(); }
    public int getThinkingBudgetTokens() { return thinkingConfig.getCurrentBudgetTokens(); }
}
