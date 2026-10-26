package io.github.mangi.eta.agent.plugin

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 插件链接的来源校验。
 *
 * 链接来自用户粘贴（可能是任何人给的），所以这里只做"能不能拿来下载"判断，
 * 真正的安全边界在 [PluginPackageInstaller]：下载回来的东西仍要过清单校验、
 * 路径校验、体积上限，用户还要在预览里确认一次。
 */
internal object PluginLinkSource {
    /** 与安装器的 maxArchiveBytes 保持同一量级：链接下载不该比本地导入更宽松。 */
    const val MAX_ARCHIVE_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_URL_LENGTH: Int = 2_048

    sealed interface Validation {
        data class Valid(
            val url: String,
            val host: String,
            /** https 才算是加密传输；http 允许（本机调试常见）但界面要如实标注。 */
            val secure: Boolean,
        ) : Validation

        data class Invalid(val code: String, val message: String) : Validation
    }

    fun validate(raw: String): Validation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Validation.Invalid("LINK_EMPTY", "链接为空")
        if (trimmed.length > MAX_URL_LENGTH) {
            return Validation.Invalid("LINK_TOO_LONG", "链接超过 $MAX_URL_LENGTH 个字符")
        }
        val uri = runCatching { URI(trimmed) }.getOrElse {
            return Validation.Invalid("LINK_INVALID", "链接格式无法解析")
        }
        val scheme = uri.scheme?.lowercase()
            ?: return Validation.Invalid("LINK_INVALID", "链接缺少协议（需要 https://）")
        if (scheme != "https" && scheme != "http") {
            return Validation.Invalid("LINK_UNSUPPORTED_SCHEME", "只支持 http/https 链接，当前是 $scheme")
        }
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return Validation.Invalid("LINK_NO_HOST", "链接缺少主机名")
        if (uri.userInfo != null) {
            // 带 user:pass@ 的链接会把凭据留在输入框与日志里，这里直接拒绝。
            return Validation.Invalid("LINK_USER_INFO", "链接不能包含用户名或密码")
        }
        return Validation.Valid(trimmed, host, scheme == "https")
    }
}

/**
 * 把插件包从链接下载到本地暂存文件（不安装）。
 *
 * 为什么不直接 `installLocalZip(openStream = { URL.openStream() })`：
 * 1. 安装器边读流边解包，网络中断会得到"归档损坏"这种没信息量的失败；
 * 2. 预览需要先在本地拿到**稳定的一份字节**（算 SHA-256、解析清单），
 *    用户确认后安装的必须是同一份文件，不能重新下载一次。
 *
 * 重定向自己跟（[OkHttpClient.Builder.followRedirects] 关闭），每一跳都重新过
 * [PluginLinkSource.validate]：默认跟随会把 https 悄悄降级成 http。
 */
internal class PluginLinkDownloader(
    private val cacheRoot: File,
    private val logger: AgentLogger = AndroidAgentLogger,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    sealed interface Outcome {
        data class Downloaded(
            val file: File,
            /** 最终地址（跟完跳转后的那个）：记来源时要记它，而不是用户输入的那一跳。 */
            val url: String,
            val sha256: String,
            val bytes: Long,
            val host: String,
            val secure: Boolean,
        ) : Outcome

        data class Failed(val code: String, val message: String) : Outcome
    }

    fun cleanup(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    suspend fun download(
        rawUrl: String,
        onProgress: suspend (downloadedBytes: Long) -> Unit = {},
    ): Outcome {
        var target: File? = null
        try {
            var url = when (val validation = PluginLinkSource.validate(rawUrl)) {
                is PluginLinkSource.Validation.Valid -> validation
                is PluginLinkSource.Validation.Invalid ->
                    return Outcome.Failed(validation.code, validation.message)
            }
            var hops = 0
            while (true) {
                coroutineContext.ensureActive()
                val request = Request.Builder()
                    .url(url.url)
                    .header("Accept", "application/zip, application/x-zip-compressed, application/octet-stream")
                    .header("User-Agent", "Eta-Plugin-Installer")
                    .get()
                    .build()
                var redirected = false
                httpClient.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        if (hops >= MAX_REDIRECTS) {
                            return Outcome.Failed("LINK_TOO_MANY_REDIRECTS", "重定向超过 $MAX_REDIRECTS 次")
                        }
                        hops++
                        val location = response.header("Location")
                            ?: return Outcome.Failed("LINK_BAD_REDIRECT", "服务器要求跳转但没有给地址")
                        val resolved = runCatching {
                            URI(url.url).resolve(location).toString()
                        }.getOrNull() ?: return Outcome.Failed("LINK_BAD_REDIRECT", "跳转地址无法解析")
                        url = when (val next = PluginLinkSource.validate(resolved)) {
                            is PluginLinkSource.Validation.Valid -> next
                            is PluginLinkSource.Validation.Invalid -> return Outcome.Failed(
                                next.code,
                                "跳转到了不支持的地址：${next.message}",
                            )
                        }
                        redirected = true
                        return@use
                    }
                    if (!response.isSuccessful) {
                        return Outcome.Failed("LINK_HTTP_${response.code}", "服务器返回 HTTP ${response.code}")
                    }
                    val body = response.body
                    val declared = body.contentLength()
                    if (declared > PluginLinkSource.MAX_ARCHIVE_BYTES) {
                        return Outcome.Failed(
                            "LINK_TOO_LARGE",
                            "插件包超过 ${PluginLinkSource.MAX_ARCHIVE_BYTES / 1024 / 1024} MB",
                        )
                    }
                    cacheRoot.mkdirs()
                    val file = File(cacheRoot, "plugin-link-${UUID.randomUUID()}.zip")
                    target = file
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var lastReported = 0L
                    body.byteStream().use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read.toLong()
                                if (total > PluginLinkSource.MAX_ARCHIVE_BYTES) {
                                    file.delete()
                                    return Outcome.Failed(
                                        "LINK_TOO_LARGE",
                                        "插件包超过 ${PluginLinkSource.MAX_ARCHIVE_BYTES / 1024 / 1024} MB，已中止",
                                    )
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                if (total - lastReported >= PROGRESS_INTERVAL_BYTES) {
                                    lastReported = total
                                    onProgress(total)
                                }
                            }
                        }
                    }
                    if (total <= 0L) {
                        file.delete()
                        return Outcome.Failed("LINK_EMPTY_BODY", "链接没有返回任何内容")
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    logger.info(
                        "Plugin link action=download host=${url.host} bytes=$total outcome=succeeded",
                    )
                    return Outcome.Downloaded(file, url.url, sha, total, url.host, url.secure)
                }
                if (!redirected) {
                    return Outcome.Failed("LINK_DOWNLOAD_FAILED", "下载没有产生可用内容")
                }
            }
        } catch (cancellation: CancellationException) {
            target?.let { runCatching { it.delete() } }
            throw cancellation
        } catch (throwable: Throwable) {
            target?.let { runCatching { it.delete() } }
            logger.warn(
                "Plugin link action=download outcome=failed errorType=${throwable.safeLogType()}",
            )
            return Outcome.Failed(
                "LINK_DOWNLOAD_FAILED",
                (throwable.message ?: throwable.javaClass.simpleName).take(200),
            )
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                // 自己跟跳转：默认跟随会把每一跳的协议降级检查让过去。
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }
}
