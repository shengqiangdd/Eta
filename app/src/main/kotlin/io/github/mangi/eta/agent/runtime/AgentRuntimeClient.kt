package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 入口进程侧的 Runtime 客户端。
 *
 * 它只负责把一次 Agent 请求交给模块进程，并把事件/结果带回入口适配层；
 * 不执行模型、不执行工具、不渲染 UI。
 */
internal class AgentRuntimeClient(
    private val context: Context,
    private val logger: AgentLogger
) {
    sealed interface AttachOutcome {
        data class Completed(val result: AgentRuntimeWire.RunResult) : AttachOutcome
        data object NotActive : AttachOutcome
        data object Unavailable : AttachOutcome
    }

    sealed interface ActiveRunQuery {
        /**
         * [paused] 只在 [runId] 非空时才有意义。
         *
         * [runningCount] 是服务端仍在执行的 run 总数：`runId` 为空只说明没有 primary，
         * 后台会话可能还在跑，不能据此判「全部结束」。
         */
        data class Known(
            val runId: String?,
            val paused: Boolean = false,
            val runningCount: Int = AgentRuntimeWire.UNKNOWN_RUN_COUNT,
        ) : ActiveRunQuery
        data object Unavailable : ActiveRunQuery
    }

    sealed interface ActiveRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.ActiveRunSnapshot>) : ActiveRunsQuery {
            val runIds: Set<String> get() = runs.mapTo(linkedSetOf()) { it.runId }
        }
        data object Unavailable : ActiveRunsQuery
    }

    sealed interface CompletedRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.CompletedRun>) : CompletedRunsQuery
        data object Unavailable : CompletedRunsQuery
    }

    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit
    ): AgentRuntimeWire.RunResult {
        val resultLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        val resultRef = AgentResultMailbox()
        val preparedImagesRef = AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val clientMessenger = Messenger(
            ClientHandler(
                onEvent = onEvent,
                onResult = { result ->
                    resultRef.set(result)
                    // 服务端在"请求非法/调用方被拒"等早期失败路径只回结果、不回 ingested，
                    // 只唤醒 resultLatch 会让界面白等 30 秒。结果既然到了就直接结束等待。
                    startedLatch.countDown()
                    resultLatch.countDown()
                },
                onRequestIngested = {
                    preparedImagesRef.getAndSet(null)?.close()
                    startedLatch.countDown()
                },
            )
        )

        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务绑定失败")
        val serviceMessenger = lease.messenger
        val deathRecipient = IBinder.DeathRecipient {
            if (resultRef.get() == null) {
                resultRef.set(
                    AgentRuntimeWire.toBundle(AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 服务连接已断开", contextSnapshotRef = request.runId))
                )
                resultLatch.countDown()
            }
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_START_RUN)
            msg.replyTo = clientMessenger
            val preparedImages = AgentRuntimeImageTransfer.prepare(context, request.images)
            preparedImagesRef.set(preparedImages)
            // 用户按下发送后最直观的等待就在这里：整个历史要序列化成 JSON（§32.26）。
            val bundleStartedAt = android.os.SystemClock.elapsedRealtime()
            msg.data = AgentRuntimeWire.toBundle(request, preparedImages.images, context.cacheDir)
            val historyChars = request.history.sumOf {
                it.content.length + it.contentJson.length + it.reasoningContent.length + it.toolCallsJson.length
            }
            // 落盘而不是只打 logcat：main ring buffer 只有 256 KiB，几十秒后这条就会被
            // DEBUG 事件顶掉，事后想对账根本抓不到（§32.31）。
            AndroidAgentLogger.infoPersisted(
                "request bundle: build=${android.os.SystemClock.elapsedRealtime() - bundleStartedAt}ms " +
                    "messages=${request.history.size} chars=$historyChars",
            )
            AgentWireText.send(serviceMessenger, msg)
            // Messenger 是 oneway：目标服务已销毁时发送不会抛异常，请求会静默消失。
            // 服务侧收到请求会回一次 ingested，等不到就说明这条链路没通，必须报错而不是
            // 无限等结果（用户看到的是界面卡在“停止”，既没有回答也没法重试）。
            if (!startedLatch.await(START_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 确认消息丢失不等于请求没送到：服务刚被拉起或主线程繁忙时，ingested 可能
                // 晚于超时才回。此时若直接报失败，用户重发会和真正在跑的那一轮撞车
                // 多会话并发后，先问服务“指定的 run 是否还活跃”，是本次就继续等结果。
                val activeRunId = when (val active = queryActiveRun(request.runId)) {
                    is ActiveRunQuery.Known -> active.runId
                    ActiveRunQuery.Unavailable -> null
                }
                if (shouldContinueAfterAckTimeout(activeRunId, request.runId)) {
                    logger.warn(
                        "Agent runtime start ack missing after ${START_ACK_TIMEOUT_SECONDS}s but run is active; waiting for result"
                    )
                    resultLatch.await()
                    return resultRef.get()?.let(AgentRuntimeWire::runResultFromBundle)
                        ?: AgentRuntimeWire.RunResult(
                            runId = request.runId,
                            ok = false,
                            content = "",
                            error = "Agent Runtime 未返回结果",
                            contextSnapshotRef = request.runId,
                        )
                }
                logger.warn("Agent runtime did not accept run request within ${START_ACK_TIMEOUT_SECONDS}s")
                return AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    ok = false,
                    content = "",
                    error = "后台 Agent 服务没有响应，任务没有启动，请重试",
                    contextSnapshotRef = request.runId,
                )
            }
            // 最终结果或 Binder 断连负责唤醒；正常长任务不因客户端等待时长被取消。
            resultLatch.await()
            return resultRef.get()?.let(AgentRuntimeWire::runResultFromBundle) ?: AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 未返回结果")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            // 客户端（界面）被取消只说明没人再等结果，不等于用户要停止运行：
            // 运行体在 Runtime 服务里继续跑，结束后的结果留在 completed runs 里，
            // 界面回来后仍能取回（AgentAppState.refreshRuntimeResults）。
            // 用户点“停止”走的是 cancelRun()，那是显式的 MSG_CANCEL，不受这里影响。
            logger.debug {
                "Agent runtime client detached; run continues (runId prefix=${request.runId.take(24)})"
            }
            return AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = "界面已离开，运行继续",
                contextSnapshotRef = request.runId,
            )
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime start request failed: type=${throwable.safeLogType()}")
            return AgentRuntimeWire.RunResult(
                runId = request.runId,
                contextSnapshotRef = request.runId,
                ok = false,
                content = "",
                error = when (throwable) {
                    is AgentRuntimeWire.PayloadTooLargeException -> throwable.message
                    is AgentRuntimeImageTransfer.ImageTransferException -> throwable.message
                    else -> "Agent Runtime 请求发送失败（${throwable.safeLogType()}）"
                },
            )
        } finally {
            resultRef.close()
            preparedImagesRef.getAndSet(null)?.close()
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    /**
     * 给正在执行的 run 追加一条用户补充指令（steering）。
     * 与“停止再重发”不同，它不打断当前步骤：AgentLoop 在步骤边界轮询补充并继续同一轮。
     */
    fun sendSupplement(runId: String, text: String): Boolean {
        if (runId.isBlank() || text.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_SUPPLEMENT)
            msg.data = AgentRuntimeWire.supplementBundle(runId, text)
            serviceMessenger.send(msg)
            true
        }
    }

    fun pauseRun(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_PAUSE_RUN)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }

    fun resumeRun(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESUME_RUN)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }

    /**
     * 运行中热切换模型 / 思考等级 / provider。
     *
     * 不重发请求、不打断当前回合：Runtime 把新配置存在 run 上，下一个模型回合才用。
     * 返回 true 只代表消息已交给 Runtime；真正生效在下一轮（调用方文案必须说「下一步生效」）。
     */
    fun updateRunModel(runId: String, config: AgentModelClient.ModelConfig): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_UPDATE_MODEL)
            msg.data = AgentRuntimeWire.toLegacyBundle(
                AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = "",
                    config = config,
                    images = emptyList(),
                )
            )
            serviceMessenger.send(msg)
            true
        }
    }

    fun ackResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ACK_RESULT)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }

    fun drainCompletedRuns(): List<AgentRuntimeWire.CompletedRun> {
        return when (val query = queryCompletedRuns()) {
            is CompletedRunsQuery.Known -> query.runs
            CompletedRunsQuery.Unavailable -> emptyList()
        }
    }

    fun queryCompletedRuns(): CompletedRunsQuery {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<List<AgentRuntimeWire.CompletedRun>>(emptyList())
        val clientMessenger = Messenger(
            DrainHandler { results ->
                resultRef.set(results)
                resultLatch.countDown()
            }
        )

        return withRuntimeMessenger<CompletedRunsQuery>(CompletedRunsQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS)
            msg.data = Bundle().apply { putBoolean("complete_result_refs", true) }
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (resultLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 服务端把每条结果的 contextSnapshotRef 都填成 runId，所以每条都要再来取一次正文。
                // 只要有一条读取慢/失败就整批 Unavailable，会把所有结果一起卡住：
                // 真机上 4 条 outbox 结果里有一条约 4.7MB，8 秒读不完，另外 3 条小的永远等不到 ack，
                // 对应的「任务已中断」提示也就永远清不掉。
                // 单条失败只是这一次跳过（不 ack，仍留在 outbox 里下次重试），不影响其余结果。
                val resolved = resultRef.get().mapNotNull { completed ->
                    if (completed.result.contextSnapshotRef.isBlank()) {
                        completed
                    } else {
                        readCompletedResult(serviceMessenger, completed)
                            ?.let { full -> completed.copy(result = full) }
                    }
                }
                CompletedRunsQuery.Known(resolved)
            } else {
                CompletedRunsQuery.Unavailable
            }
        }
    }

    private fun readCompletedResult(
        service: Messenger,
        completed: AgentRuntimeWire.CompletedRun,
    ): AgentRuntimeWire.RunResult? = AgentResultMailbox().use { mailbox ->
        val received = CountDownLatch(1)
        val receiver = Messenger(ClientHandler(onEvent = {}, onResult = {
            mailbox.set(it)
            received.countDown()
        }, onRequestIngested = {}))
        service.send(Message.obtain(null, AgentRuntimeWire.MSG_READ_CONTEXT_RESULT).apply {
            replyTo = receiver
            data = AgentRuntimeWire.ackBundle(completed.result.runId).apply {
                putString("context_owner", completed.handoff.payload)
            }
        })
        if (!received.await(READ_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return@use null
        val bundle = mailbox.get() ?: return@use null
        AgentRuntimeWire.runResultFromBundle(bundle).takeIf {
            it.runId == completed.result.runId && it.contextSnapshotRef.isBlank()
        }
    }

    fun queryActiveRun(runId: String = ""): ActiveRunQuery {
        val responseLatch = CountDownLatch(1)
        val runIdRef = AtomicReference("")
        val pausedRef = AtomicReference(false)
        val runningCountRef = AtomicReference(AgentRuntimeWire.UNKNOWN_RUN_COUNT)
        val clientMessenger = Messenger(
            ActiveRunHandler { activeRunId, paused, runningCount ->
                runIdRef.set(activeRunId)
                pausedRef.set(paused)
                runningCountRef.set(runningCount)
                responseLatch.countDown()
            }
        )

        return withRuntimeMessenger<ActiveRunQuery>(ActiveRunQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN)
            msg.replyTo = clientMessenger
            if (runId.isNotBlank()) msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ActiveRunQuery.Unavailable
            } else {
                val activeRunId = runIdRef.get().takeIf(String::isNotBlank)
                ActiveRunQuery.Known(
                    runId = activeRunId,
                    paused = activeRunId != null && pausedRef.get(),
                    runningCount = runningCountRef.get().coerceAtLeast(0),
                )
            }
        }
    }

    fun queryActiveRuns(): ActiveRunsQuery {
        val responseLatch = CountDownLatch(1)
        val response = AtomicReference<List<AgentRuntimeWire.ActiveRunSnapshot>>()
        val clientMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE) return
                response.set(AgentRuntimeWire.activeRunsFromBundle(msg.data ?: Bundle()))
                responseLatch.countDown()
            }
        })
        return withRuntimeMessenger<ActiveRunsQuery>(ActiveRunsQuery.Unavailable) { serviceMessenger ->
            serviceMessenger.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS).apply {
                replyTo = clientMessenger
            })
            if (responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ActiveRunsQuery.Known(response.get().orEmpty())
            } else {
                ActiveRunsQuery.Unavailable
            }
        }
    }

    /** 历史一次性交给 onReplay；未指定时沿用 onEvent。后续新增事件始终交给 onEvent。 */
    fun attachRun(
        runId: String,
        onReplay: ((List<AgentEvent>) -> Unit)? = null,
        onEvent: (AgentEvent) -> Unit,
    ): AttachOutcome {
        if (runId.isBlank()) return AttachOutcome.NotActive
        val terminalLatch = CountDownLatch(1)
        val attachLatch = CountDownLatch(1)
        val attachedRef = AtomicReference<Boolean?>(null)
        val resultRef = AgentResultMailbox()
        val clientMessenger = Messenger(
            AttachHandler(
                onReplay = onReplay,
                onEvent = onEvent,
                onAttachResponse = { attached ->
                    attachedRef.set(attached)
                    attachLatch.countDown()
                    if (!attached) terminalLatch.countDown()
                },
                onResult = { result ->
                    resultRef.set(result)
                    attachLatch.countDown()
                    terminalLatch.countDown()
                },
            )
        )
        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AttachOutcome.Unavailable
        val deathRecipient = IBinder.DeathRecipient {
            attachLatch.countDown()
            terminalLatch.countDown()
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN)
            msg.replyTo = clientMessenger
            msg.data = AgentRuntimeWire.ackBundle(runId)
            lease.messenger.send(msg)
            if (!attachLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return AttachOutcome.Unavailable
            }
            terminalLatch.await()
            resultRef.get()?.let { return AttachOutcome.Completed(AgentRuntimeWire.runResultFromBundle(it)) }
            return if (attachedRef.get() == false) {
                AttachOutcome.NotActive
            } else {
                AttachOutcome.Unavailable
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AttachOutcome.Unavailable
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime attach failed: type=${throwable.safeLogType()}")
            return AttachOutcome.Unavailable
        } finally {
            resultRef.close()
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun <T> withRuntimeMessenger(defaultValue: T, block: (Messenger) -> T): T {
        val lease = AgentRuntimeConnection.acquire(context, logger) ?: return defaultValue
        try {
            return block(lease.messenger)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return defaultValue
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime service call failed: type=${throwable.safeLogType()}")
            return defaultValue
        } finally {
            lease.close()
        }
    }

    private class ClientHandler(
        private val onEvent: (AgentEvent) -> Unit,
        private val onResult: (Bundle) -> Unit,
        private val onRequestIngested: () -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT -> {
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(onEvent)
                }

                AgentRuntimeWire.MSG_RESULT -> {
                    onResult(msg.data ?: return)
                }

                AgentRuntimeWire.MSG_REQUEST_INGESTED -> onRequestIngested()
            }
        }
    }

    private class DrainHandler(
        private val onResults: (List<AgentRuntimeWire.CompletedRun>) -> Unit
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE) {
                onResults(AgentRuntimeWire.completedRunsFromBundle(msg.data ?: return))
            }
        }
    }

    private class ActiveRunHandler(
        private val onResponse: (String, Boolean, Int) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE) {
                val data = msg.data ?: return
                onResponse(
                    AgentRuntimeWire.runIdFromBundle(data),
                    AgentRuntimeWire.runPausedFromBundle(data),
                    AgentRuntimeWire.runningCountFromBundle(data),
                )
            }
        }
    }

    private class AttachHandler(
        onReplay: ((List<AgentEvent>) -> Unit)?,
        onEvent: (AgentEvent) -> Unit,
        onAttachResponse: (Boolean) -> Unit,
        private val onResult: (Bundle) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        private val delivery = AgentRuntimeAttachDelivery(
            onReplay = onReplay,
            onEvent = onEvent,
            onAttachResponse = onAttachResponse,
            onResult = {},
        )

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT ->
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(delivery::event)
                AgentRuntimeWire.MSG_RESULT ->
                    if (delivery.beginResult()) onResult(msg.data ?: return)
                AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE ->
                    delivery.attachResponse(AgentRuntimeWire.attachRunSucceeded(msg.data ?: return))
            }
        }
    }

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 8L
        /** 读取单条完整结果（可能带 MB 级 transcript）的等待上限，不能按轻量查询的 8 秒算。 */
        const val READ_RESULT_TIMEOUT_SECONDS = 30L
        /** 服务确认收到请求的上限；图片准备也算在内，不能压得太短。 */
        const val START_ACK_TIMEOUT_SECONDS = 30L
    }
}

/**
 * “服务已接收请求”的确认超时后，是否还应继续等待结果。
 *
 * 确认消息与请求走同一条 oneway 通道：服务刚被拉起或主线程繁忙时，确认可能晚于超时到达。
 * 只有服务明确回了“当前活跃 run 就是本次请求”才继续等；服务不可达或活跃的是别的请求时
 * 仍按失败处理，避免用户对着一个根本没启动的任务干等。
 */
internal fun shouldContinueAfterAckTimeout(activeRunId: String?, requestRunId: String): Boolean =
    !activeRunId.isNullOrBlank() && activeRunId == requestRunId
