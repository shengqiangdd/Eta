package io.github.mangi.eta.ui.screens.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.launch

/** WebView 保持在固定窗口内，进出动画由系统合成窗口，避免整页绘制裁剪介入网页渲染。 */
class AgentBrowserActivity : ComponentActivity() {

    /**
     * 页面打开期间持有挂载引用，销毁时释放。
     *
     * 不持有的话，会话注册表就不知道「这个页面还有人看着」，可以把它当成空闲会话回收掉
     * ——用户在面板里看着的页面就会凭空变成空白。
     */
    private var mountBinding: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY)?.takeIf { it.isNotBlank() }
        if (sessionKey == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        mountBinding = BrowserSessionRegistry.mount(sessionKey)
        val session = BrowserSessionRegistry.get(sessionKey)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initialAppearance)
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = { isDark ->
                        val style = if (isDark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        }
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    },
                ) {
                    AgentBrowserScreen(session = session, onBack = ::finish)
                }
            }
        }
    }

    override fun onDestroy() {
        // 先释放挂载引用再交给 super：让注册表能在本次销毁里就把会话标成可回收，
        // 而不是拖到下一次内存压力回调。
        mountBinding?.let { runCatching { it.close() } }
        mountBinding = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SESSION_KEY = "io.github.mangi.eta.extra.BROWSER_SESSION_KEY"

        fun createIntent(context: Context, sessionKey: String): Intent {
            require(sessionKey.isNotBlank())
            return Intent(context, AgentBrowserActivity::class.java).putExtra(EXTRA_SESSION_KEY, sessionKey)
        }
    }
}
