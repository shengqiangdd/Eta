package io.github.mangi.eta.ui.app

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 分支的命令拼装。
 *
 * 这条路径的命令拼装成本低、后果重：少转义一层引号，轻则导入/导出失败，重则让文件名
 * 参与命令执行；漏掉 `-f` 会把目录当成文件复制。断言写成「关键子句必须出现」而不是
 * 整串比对，改动命令顺序时不至于假红。
 */
class WorkspaceRootCommandTest {

    @Test
    fun importCommandChecksFileBeforeMeasuringSize() {
        val command = rootImportCommand("/sdcard/Download/note.txt", "/data/x/imports/1/note.txt", 32L * 1024 * 1024)
        val fileCheck = command.indexOf("[ -f ")
        val sizeCheck = command.indexOf("wc -c")
        assertTrue("缺少 -f 检查：$command", fileCheck >= 0)
        assertTrue("缺少大小检查：$command", sizeCheck >= 0)
        assertTrue("`-f` 必须排在取大小之前，否则目录也会被 cp：$command", fileCheck < sizeCheck)
        assertTrue("上限值没写进命令：$command", command.contains("-le 33554432"))
        assertTrue("缺少复制：$command", command.contains("cp -f "))
        assertTrue("退出码约定变了：$command", command.contains("exit 3") && command.contains("exit 4") && command.contains("exit 5"))
    }

    @Test
    fun importCommandQuotesPathsWithSpacesAndQuotes() {
        val tricky = "/sdcard/My Files/it's \$HOME.txt"
        val command = rootImportCommand(tricky, "/data/x/target.txt", 1)
        // 单引号包住时，`$` 与空格都不参与展开；名字里的单引号要变成 '\'' 才能闭合再重开。
        assertTrue("路径没有整体加引号：$command", command.contains("'/sdcard/My Files/it'\\''s \$HOME.txt'"))
    }

    @Test
    fun listNamesCommandEscapesQuotes() {
        val command = rootListNamesCommand("/storage/emulated/0/it's")
        assertTrue("目录没有整体加引号：$command", command.contains("cd '/storage/emulated/0/it'\\''s'"))
        assertTrue("缺少 ls：$command", command.contains("ls -1a"))
        assertTrue("目录不可读时应给出非零退出码：$command", command.contains("exit 3"))
    }

    @Test
    fun exportCommandReservesTheNameAtomicallyBeforeWriting() {
        val command = rootExportCommand("/data/x/note.txt", "/sdcard/out/note.txt")
        assertTrue("目标存在时必须先退出：$command", command.startsWith("[ -e '/sdcard/out/note.txt' ] && exit 6; "))
        val reserve = command.indexOf("(set -C; : > '/sdcard/out/note.txt')")
        val write = command.indexOf("cat '/data/x/note.txt' > '/sdcard/out/note.txt'")
        assertTrue("缺少 noclobber 原子占位：$command", reserve >= 0)
        assertTrue("缺少写入：$command", write >= 0)
        // 「先探再写」正是要被关掉的窗口，所以顺序与失败码都得钉住。
        assertTrue("必须先占位再写：$command", reserve < write)
        assertTrue("占位失败要归为「占用」(6)：$command", command.substring(reserve, write).contains("exit 6"))
        assertTrue("写失败要清掉占位：$command", command.contains("rm -f '/sdcard/out/note.txt'; exit 5"))
        // `cp -f` 会先 unlink 再创建，等于把刚关上的窗口重开。
        assertTrue("不应再用 cp -f：$command", !command.contains("cp -f"))
    }

    @Test
    fun importCommandHandsTheFileBackToTheAppAfterCopying() {
        // root 的 `cp` 落盘属主是 root（本机实测 -rw-r-----），一旦 root 授权不可用，
        // 这份数据就再也读不出来 —— 所以复制之后必须把属主改回应用。
        val command = rootImportCommand("/sdcard/Download/note.txt", "/data/x/imports/1/note.txt", 1, ownerUid = 10395)
        val copy = command.indexOf("cp -f '/sdcard/Download/note.txt' '/data/x/imports/1/note.txt'")
        val chown = command.indexOf("chown 10395:10395 '/data/x/imports/1/note.txt'")
        assertTrue("缺少复制：$command", copy >= 0)
        assertTrue("缺少改属主：$command", chown >= 0)
        assertTrue("必须先复制再改属主：$command", copy < chown)
        // 改属主失败要删掉刚复制的文件：留一个应用读不到的半成品，比明确失败更糟。
        assertTrue(
            "改属主失败时没有清理：$command",
            command.substring(chown).contains("|| { rm -f '/data/x/imports/1/note.txt'; exit 5; }"),
        )
    }

    @Test
    fun importCommandWithoutOwnerUidKeepsTheOldShape() {
        // 不传 uid 时不应凭空 chown 一个「0:0」——那会把文件改回 root 属主。
        assertTrue(
            "未传 uid 时不应出现 chown：${rootImportCommand(
                "/sdcard/Download/note.txt",
                "/data/x/target.txt",
                1,
            )}",
            !rootImportCommand("/sdcard/Download/note.txt", "/data/x/target.txt", 1).contains("chown"),
        )
    }

    @Test
    fun differentPathsProduceDifferentCommands() {
        // 反向自测：如果拼装把路径丢掉，上面那些断言可能仍然「碰巧」通过。
        assertNotEquals(
            rootExportCommand("/data/x/a.txt", "/sdcard/out/a.txt"),
            rootExportCommand("/data/x/b.txt", "/sdcard/out/b.txt"),
        )
    }
}
