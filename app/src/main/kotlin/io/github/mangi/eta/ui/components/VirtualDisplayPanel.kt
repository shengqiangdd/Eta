package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.VirtualDisplayHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * App 内的虚拟屏预览面板（不是悬浮窗）。
 *
 * 用户把 Eta 当小窗用时，预览必须跟着 Eta 的窗口走；悬浮窗会独立占屏，
 * 还会遮挡主屏内容，所以这里做成应用内的可折叠卡片：
 * - 右上角一条标题栏，点一下展开/收起；
 * - 展开时按 700ms 刷新一帧（缩放解码，避免每帧 8MB）；
 * - 只在存在虚拟屏会话时出现，虚拟屏释放后自动消失。
 */
@Composable
internal fun VirtualDisplayPanel(modifier: Modifier = Modifier) {
    val session by VirtualDisplayHub.session.collectAsState()
    val current = session ?: return
    var expanded by rememberSaveable(current.id) { mutableStateOf(false) }
    var bitmap by remember(current.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(current.id, expanded) {
        if (!expanded) {
            bitmap = null
            return@LaunchedEffect
        }
        while (isActive) {
            val bytes = withContext(Dispatchers.IO) { VirtualDisplayHub.capture() }
            if (bytes != null) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 3
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val decoded = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }.getOrNull()
                if (decoded != null) bitmap = decoded
            }
            delay(1_000)
        }
    }
    val purposeLabel = current.purpose.ifBlank { stringResource(R.string.virtual_display_running) }
    Column(
        modifier = modifier
            .width(180.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.virtual_display_title_expanded, purposeLabel)
                } else {
                    stringResource(R.string.virtual_display_title_collapsed, purposeLabel)
                },
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) stringResource(R.string.virtual_display_collapse) else stringResource(R.string.virtual_display_expand),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 32.dp),
            )
        }
        if (expanded) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.virtual_display_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                // 空屏或首帧还没到时，明确告诉用户「在等画面」，
                // 而不是只留一个没有内容的展开卡片，看起来像坏了。
                Text(
                    text = stringResource(R.string.virtual_display_waiting),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                )
            }
        }
    }
}
