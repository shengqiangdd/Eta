package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageQueueReducerTest {

    @Test
    fun enqueueAppendsInOrderAndClearsCurrentDraft() {
        val image = PendingImageUi(id = "img-1", uri = "file:///1.jpg", dataUrl = "data:image/jpeg;base64,1", mimeType = "image/jpeg")
        val initial = baseState(
            isStreaming = true,
            input = "草稿",
            images = listOf(image),
        )

        val firstState = AgentMessageQueueReducer.enqueue(
            state = initial,
            item = QueuedMessageUi(id = "q-1", content = "第一条", pendingImages = listOf(image)),
        )
        val secondState = AgentMessageQueueReducer.enqueue(
            state = firstState,
            item = QueuedMessageUi(id = "q-2", content = "第二条"),
        )

        assertEquals("", secondState.input)
        assertTrue(secondState.pendingImages.isEmpty())
        assertEquals(listOf("q-1", "q-2"), secondState.queuedMessages.map { it.id })
    }

    /**
     * 输入栏不再自己 clearText（它无法知道这次提交会不会被拒），所以「草稿被消费」必须自带
     * 同步信号：`input` 清空 + `inputSyncVersion + 1`。少了后者，输入框会一直停在刚排队的那段文字上，
     * 用户在界面上看到「排了队、字还在」，再点一次就排两条。
     */
    @Test
    fun enqueueConsumesDraftAndSignalsComposerToSync() {
        val initial = baseState(isStreaming = true, input = "排队这条").copy(inputSyncVersion = 7)

        val next = AgentMessageQueueReducer.enqueue(
            state = initial,
            item = QueuedMessageUi(id = "q-1", content = "排队这条"),
        )

        assertEquals("", next.input)
        assertEquals(8, next.inputSyncVersion)
        assertEquals(listOf("q-1"), next.queuedMessages.map { it.id })
    }

    @Test
    fun immediateInterruptPreemptsQueueHeadAndDemotesPreviousInterrupt() {
        val state = baseState(isStreaming = true).let {
            val s1 = AgentMessageQueueReducer.enqueue(it, QueuedMessageUi(id = "q-1", content = "普通排队"))
            AgentMessageQueueReducer.enqueue(s1, QueuedMessageUi(id = "q-2", content = "第一次插话", immediateInterrupt = true))
        }
        assertEquals(listOf("q-2", "q-1"), state.queuedMessages.map { it.id })
        assertTrue(state.queuedMessages[0].immediateInterrupt)

        val next = AgentMessageQueueReducer.enqueue(
            state = state,
            item = QueuedMessageUi(id = "q-3", content = "第二次插话", immediateInterrupt = true),
        )
        assertEquals(listOf("q-3", "q-2", "q-1"), next.queuedMessages.map { it.id })
        assertTrue(next.queuedMessages[0].immediateInterrupt)
        assertFalse(next.queuedMessages[1].immediateInterrupt)
    }

    @Test
    fun promoteToInterruptMovesItemToFrontWhenStreamingAndPopsImmediatelyWhenIdle() {
        val queued = listOf(
            QueuedMessageUi(id = "q-1", content = "第一条"),
            QueuedMessageUi(id = "q-2", content = "第二条"),
        )
        val streamingState = baseState(isStreaming = true).copy(queuedMessages = queued)
        val (promotedStreaming, promotedItem) = AgentMessageQueueReducer.promoteToInterrupt(streamingState, "q-2")
        assertNotNull(promotedItem)
        assertEquals(listOf("q-2", "q-1"), promotedStreaming.queuedMessages.map { it.id })
        assertTrue(promotedStreaming.queuedMessages.first().immediateInterrupt)

        val idleState = baseState(isStreaming = false).copy(queuedMessages = queued)
        val (afterIdlePop, poppedItem) = AgentMessageQueueReducer.promoteToInterrupt(idleState, "q-2")
        assertNotNull(poppedItem)
        assertFalse(poppedItem!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterIdlePop.queuedMessages.map { it.id })
    }

    @Test
    fun recallToDraftMergesWithUnsentTextAndAttachmentsAndBumpsSyncVersion() {
        val existingImage = PendingImageUi(id = "img-1", uri = "u1", dataUrl = "d1", mimeType = "image/png")
        val queuedImage = PendingImageUi(id = "img-2", uri = "u2", dataUrl = "d2", mimeType = "image/png")
        val queuedFile = PendingFileReferenceUi(
            id = "file-1",
            reference = AgentFileReference(
                displayName = "doc.txt",
                absolutePath = "/sdcard/doc.txt",
                kind = AgentFileReferenceKind.File,
            ),
        )
        val state = baseState(
            isStreaming = true,
            input = "",
            images = listOf(existingImage),
        ).copy(
            queuedMessages = listOf(
                QueuedMessageUi(
                    id = "q-1",
                    content = "撤回的内容",
                    pendingImages = listOf(existingImage, queuedImage),
                    pendingFileReferences = listOf(queuedFile),
                ),
                QueuedMessageUi(id = "q-2", content = "保留的内容"),
            ),
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(
            state = state,
            currentInputText = "正在敲的半句话",
            id = "q-1",
        )
        assertNotNull(recalled)
        assertEquals("正在敲的半句话\n撤回的内容", recalled!!.input)
        assertEquals(listOf("img-1", "img-2"), recalled.pendingImages.map { it.id })
        assertEquals(listOf("file-1"), recalled.pendingFileReferences.map { it.id })
        assertEquals(listOf("q-2"), recalled.queuedMessages.map { it.id })
        assertEquals(state.inputSyncVersion + 1, recalled.inputSyncVersion)
    }

    @Test
    fun pollNextToSendAfterRunRespectsUserStopExceptForImmediateInterrupt() {
        val normalQueue = listOf(
            QueuedMessageUi(id = "q-1", content = "第一条"),
            QueuedMessageUi(id = "q-2", content = "第二条"),
        )
        val state = baseState(isStreaming = false).copy(queuedMessages = normalQueue)

        // 用户主动点击停止按钮：队列保留，不自动发出
        val (stoppedState, stoppedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = true,
        )
        assertNull(stoppedNext)
        assertEquals(2, stoppedState.queuedMessages.size)

        // 自然完成：自动弹出队首第 1 条
        val (completedState, completedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = false,
        )
        assertEquals("q-1", completedNext?.id)
        assertEquals(listOf("q-2"), completedState.queuedMessages.map { it.id })

        // 插话触发的中止：即使运行因取消结束，队首插话项仍立即弹出发送
        val interruptState = state.copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-interrupt", content = "立即插话", immediateInterrupt = true),
                QueuedMessageUi(id = "q-1", content = "第一条"),
            ),
        )
        val (afterInterruptState, interruptNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = interruptState,
            stoppedByUser = true,
        )
        assertEquals("q-interrupt", interruptNext?.id)
        assertFalse(interruptNext!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterInterruptState.queuedMessages.map { it.id })
    }

    /**
     * 「要不要冻结队列」是用户意图的合并点：停止按钮 = 冻结，插话 = 不冻结，
     * 哪怕 run 的 error 同样是「已停止」。这几种组合以前只能靠真机试，现在钉住。
     */
    @Test
    fun isQueueFrozenHonorsUserStopButNotInterruptStop() {
        // 点停止：冻结
        assertTrue(
            AgentMessageQueueReducer.isQueueFrozen(
                explicitlyStoppedByUser = true,
                stoppedForInterrupt = false,
                errorLooksStopped = false,
            )
        )
        // 点插话、run 以「已停止」报错：不冻结，换一条继续说
        assertFalse(
            AgentMessageQueueReducer.isQueueFrozen(
                explicitlyStoppedByUser = false,
                stoppedForInterrupt = true,
                errorLooksStopped = true,
            )
        )
        // 没点停止，但 error 恰好是「已停止」：冻结，别自己跑起来
        assertTrue(
            AgentMessageQueueReducer.isQueueFrozen(
                explicitlyStoppedByUser = false,
                stoppedForInterrupt = false,
                errorLooksStopped = true,
            )
        )
        // 自然结束：不冻结
        assertFalse(
            AgentMessageQueueReducer.isQueueFrozen(
                explicitlyStoppedByUser = false,
                stoppedForInterrupt = false,
                errorLooksStopped = false,
            )
        )
        // 两个标志都在（先停、后又点插话）：以最后一次意图为准，不冻结
        assertFalse(
            AgentMessageQueueReducer.isQueueFrozen(
                explicitlyStoppedByUser = true,
                stoppedForInterrupt = true,
                errorLooksStopped = true,
            )
        )
    }

    /**
     * `planDispatch` 的契约：调用方已经把它从队列里弹出来了，所以**每条发不出去的路径
     * 都必须把消息放回某处**（队列或输入框）。这里逐条钉住 —— 「压缩中」「带附件但
     * 草稿位被占」这两个口子以前都是直接丢消息。
     */
    @Test
    fun planDispatchSendsTextOnlyMessageWhenIdleAndDraftIsEmpty() {
        val idle = baseState(isStreaming = false)
        val (next, plan) = AgentMessageQueueReducer.planDispatch(
            state = idle,
            item = QueuedMessageUi(id = "q-1", content = "第一条"),
        )
        assertEquals(AgentMessageQueueReducer.DispatchPlan.SendNow, plan)
        // 正文由调用方塞进输入框再发，状态在这里不动
        assertSame(idle, next)
    }

    @Test
    fun planDispatchPutsMessageBackWhenStreamingOrCompacting() {
        val item = QueuedMessageUi(id = "q-1", content = "别丢")
        val streaming = baseState(isStreaming = true)
        val compacting = baseState(isStreaming = false).copy(isCompacting = true)
        for (state in listOf(streaming, compacting)) {
            val (next, plan) = AgentMessageQueueReducer.planDispatch(state, item)
            assertEquals(AgentMessageQueueReducer.DispatchPlan.Deferred, plan)
            assertEquals(listOf("q-1"), next.queuedMessages.map { it.id })
            assertEquals("", next.input)
            assertFalse(next.queuedMessages.first().immediateInterrupt)
        }
    }

    @Test
    fun planDispatchDefersWhileEditingWithoutTouchingTheEditedText() {
        val edit = MessageEditUiState(
            targetMessageId = "m-1",
            previousInput = "",
            previousImages = emptyList(),
            previousFileReferences = emptyList(),
            hasLaterTurns = false,
        )
        val state = baseState(isStreaming = true, input = "改了一半").copy(messageEdit = edit)
        val (next, plan) = AgentMessageQueueReducer.planDispatch(
            state = state,
            item = QueuedMessageUi(id = "q-1", content = "别丢"),
        )
        assertEquals(AgentMessageQueueReducer.DispatchPlan.Deferred, plan)
        assertEquals("改了一半", next.input)
        assertEquals(edit, next.messageEdit)
        assertEquals(listOf("q-1"), next.queuedMessages.map { it.id })
    }

    @Test
    fun planDispatchKeepsTheDraftWhenUserIsTyping() {
        val state = baseState(isStreaming = false, input = "我正在打的半句话")
        val (next, plan) = AgentMessageQueueReducer.planDispatch(
            state = state,
            item = QueuedMessageUi(id = "q-1", content = "排队的话", immediateInterrupt = true),
        )
        assertEquals(AgentMessageQueueReducer.DispatchPlan.Deferred, plan)
        // 草稿一个字都不能少：以前这里走 enqueue，会把 input 清空
        assertEquals("我正在打的半句话", next.input)
        assertNull(next.messageEdit)
        assertEquals(listOf("q-1"), next.queuedMessages.map { it.id })
        assertFalse(next.queuedMessages.first().immediateInterrupt)
    }

    @Test
    fun planDispatchMovesAttachmentMessageToDraftWhenTheSlotIsFree() {
        val image = PendingImageUi(id = "img-1", uri = "file:///1.jpg", dataUrl = "data:image/jpeg;base64,1", mimeType = "image/jpeg")
        val state = baseState(isStreaming = false)
        val (next, plan) = AgentMessageQueueReducer.planDispatch(
            state = state,
            item = QueuedMessageUi(id = "q-1", content = "看图", pendingImages = listOf(image)),
        )
        assertEquals(AgentMessageQueueReducer.DispatchPlan.MovedToDraft, plan)
        assertEquals("看图", next.input)
        assertEquals(listOf(image), next.pendingImages)
        assertTrue(next.queuedMessages.isEmpty())
    }

    @Test
    fun planDispatchPutsAttachmentMessageBackWhenDraftSlotIsTaken() {
        val image = PendingImageUi(id = "img-1", uri = "file:///1.jpg", dataUrl = "data:image/jpeg;base64,1", mimeType = "image/jpeg")
        val state = baseState(isStreaming = false, input = "先写着")
        val (next, plan) = AgentMessageQueueReducer.planDispatch(
            state = state,
            item = QueuedMessageUi(id = "q-1", content = "看图", pendingImages = listOf(image)),
        )
        assertEquals(AgentMessageQueueReducer.DispatchPlan.Deferred, plan)
        assertEquals("先写着", next.input)
        // 不能把附件塞进用户正在写的草稿里
        assertTrue(next.pendingImages.isEmpty())
        assertEquals(listOf("q-1"), next.queuedMessages.map { it.id })
    }

    @Test
    fun planDispatchNeverDuplicatesAMessageThatIsAlreadyQueued() {
        val item = QueuedMessageUi(id = "q-1", content = "别丢", immediateInterrupt = true)
        val state = baseState(isStreaming = true).copy(queuedMessages = listOf(item))
        val (next, plan) = AgentMessageQueueReducer.planDispatch(state, item)
        assertEquals(AgentMessageQueueReducer.DispatchPlan.Deferred, plan)
        assertSame(state, next)
        assertEquals(1, next.queuedMessages.count { it.id == "q-1" })
    }

    /**
     * 队列条「立即插话发送」在空闲时走的就是「弹出 → planDispatch」。
     * 有草稿时消息必须回到队列，用户的草稿原样保留。
     */
    @Test
    fun steerThenDispatchKeepsBothTheMessageAndTheDraft() {
        val state = baseState(isStreaming = false, input = "草稿").copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-1", content = "第一条"),
                QueuedMessageUi(id = "q-2", content = "第二条"),
            ),
        )
        val (afterPop, promoted) = AgentMessageQueueReducer.promoteToInterrupt(state, "q-2")
        assertNotNull(promoted)
        val (afterDispatch, plan) = AgentMessageQueueReducer.planDispatch(afterPop, promoted!!)
        assertEquals(AgentMessageQueueReducer.DispatchPlan.Deferred, plan)
        assertEquals("草稿", afterDispatch.input)
        // 顺序不变：q-2 回到队尾，q-1 仍是队首
        assertEquals(listOf("q-1", "q-2"), afterDispatch.queuedMessages.map { it.id })
    }

    /**
     * 有草稿时，队首的「插话中」必须降级成「排队中」。
     *
     * 这段是真机复现的：插话确实打断了一轮，但 run 收尾接力见草稿非空直接跳过，
     * 标记一直留着 —— 队列条永远显示「插话中」，消息却排在队首不动。
     */
    @Test
    fun demoteBlockedInterruptHeadClearsInterruptFlagWhenDraftBlocks() {
        val state = baseState(isStreaming = false, input = "我的草稿").copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-1", content = "插话", immediateInterrupt = true),
                QueuedMessageUi(id = "q-2", content = "排队"),
            ),
        )
        val next = AgentMessageQueueReducer.demoteBlockedInterruptHead(state)
        assertFalse(next.queuedMessages.first().immediateInterrupt)
        // 只动队首：顺序与后面的条目原样保留
        assertEquals(listOf("q-1", "q-2"), next.queuedMessages.map { it.id })
        assertFalse(next.queuedMessages[1].immediateInterrupt)
        assertEquals("我的草稿", next.input)
    }

    @Test
    fun demoteBlockedInterruptHeadKeepsFlagWhenThereIsNoDraft() {
        val state = baseState(isStreaming = true).copy(
            queuedMessages = listOf(QueuedMessageUi(id = "q-1", content = "插话", immediateInterrupt = true)),
        )
        assertSame(state, AgentMessageQueueReducer.demoteBlockedInterruptHead(state))
    }

    /** 带附件的消息走草稿位，草稿被占时本来就要等，不能把插话标记抹掉。 */
    @Test
    fun demoteBlockedInterruptHeadIgnoresAttachmentMessages() {
        val image = PendingImageUi(id = "img-1", uri = "file:///1.jpg", dataUrl = "data:image/jpeg;base64,1", mimeType = "image/jpeg")
        val state = baseState(isStreaming = true, input = "草稿").copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-1", content = "看图", immediateInterrupt = true, pendingImages = listOf(image)),
            ),
        )
        assertSame(state, AgentMessageQueueReducer.demoteBlockedInterruptHead(state))
    }

    @Test
    fun demoteBlockedInterruptHeadDoesNothingForPlainQueue() {
        val state = baseState(isStreaming = true, input = "草稿").copy(
            queuedMessages = listOf(QueuedMessageUi(id = "q-1", content = "排队")),
        )
        assertSame(state, AgentMessageQueueReducer.demoteBlockedInterruptHead(state))
    }

    private fun baseState(
        isStreaming: Boolean,
        input: String = "",
        images: List<PendingImageUi> = emptyList(),
    ) = AgentChatUiState(
        messages = emptyList(),
        input = input,
        isStreaming = isStreaming,
        thinkingEnabled = true,
        pendingImages = images,
    )
}
