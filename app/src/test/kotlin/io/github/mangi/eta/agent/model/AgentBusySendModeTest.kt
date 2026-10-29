package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 繁忙时发送行为的契约。
 *
 * 这个设置的失败方式很隐蔽：读错一个值，发送按钮还是能点、消息也还能发出去，
 * 只是行为从「排队」变成「打断」，用户会以为自己的 run 莫名被杀。
 * 所以默认值、回退、以及暂停/编辑两种例外都要用断言钉住。
 */
class AgentBusySendModeTest {

    @Test
    fun defaultIsQueue() {
        assertEquals(AgentBusySendMode.QUEUE, AgentBusySendMode.DEFAULT)
        assertEquals(AgentBusySendMode.QUEUE, AgentBusySendMode.fromId(null))
        assertEquals(AgentBusySendMode.QUEUE, AgentBusySendMode.fromId(""))
        assertEquals(AgentBusySendMode.QUEUE, AgentBusySendMode.fromId("   "))
    }

    @Test
    fun knownIdsRoundTrip() {
        AgentBusySendMode.values().forEach { mode ->
            assertEquals(mode, AgentBusySendMode.fromId(mode.id))
            assertEquals(mode, AgentBusySendMode.fromId(" ${mode.id} "))
        }
    }

    @Test
    fun unknownValueFallsBackToQueue() {
        // 手改配置文件、旧版本写入的未知 id：不能变成「什么都不发」。
        assertEquals(AgentBusySendMode.QUEUE, AgentBusySendMode.fromId("steer-now"))
        assertEquals(AgentBusySendMode.QUEUE, AgentBusySendMode.fromId("STEER"))
    }

    @Test
    fun steerOnlyWhenStreamingAndNotPausedOrEditing() {
        assertTrue(
            AgentBusySendPolicy.steers(
                streaming = true,
                paused = false,
                editingMessage = false,
                mode = AgentBusySendMode.STEER,
            ),
        )
        // 暂停中插话会打断用户想保住的现场 —— 一律排队。
        assertFalse(
            AgentBusySendPolicy.steers(
                streaming = true,
                paused = true,
                editingMessage = false,
                mode = AgentBusySendMode.STEER,
            ),
        )
        // 编辑态插话：run 被打断，编辑内容还发不出去。
        assertFalse(
            AgentBusySendPolicy.steers(
                streaming = true,
                paused = false,
                editingMessage = true,
                mode = AgentBusySendMode.STEER,
            ),
        )
        // 空闲时没有可插的话，走普通发送。
        assertFalse(
            AgentBusySendPolicy.steers(
                streaming = false,
                paused = false,
                editingMessage = false,
                mode = AgentBusySendMode.STEER,
            ),
        )
    }

    @Test
    fun queueModeNeverSteers() {
        listOf(false, true).forEach { streaming ->
            listOf(false, true).forEach { paused ->
                listOf(false, true).forEach { editing ->
                    assertFalse(
                        AgentBusySendPolicy.steers(
                            streaming = streaming,
                            paused = paused,
                            editingMessage = editing,
                            mode = AgentBusySendMode.QUEUE,
                        ),
                    )
                }
            }
        }
    }
}
