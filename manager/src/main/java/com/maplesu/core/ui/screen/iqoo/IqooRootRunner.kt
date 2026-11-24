package com.maplesu.core.ui.screen.iqoo

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * iQOO Neo 5（PD2055）提权链路。
 *
 * 这套流程来自设备专用的 exploit 包，四个二进制按顺序接力：
 *
 *   perfleak2        → 用 perf 事件泄漏内核地址，绕过 KASLR
 *   pd2055_exploit   → 走 f2fs 写路径的内核缺陷拿到 uid 0
 *   koreloc          → 按 syms.txt 把 kernelsu.ko 的未定义符号重定位到实际基址
 *   kernelsu.ko      → 提供常驻 root 通道
 *
 * 每个二进制都是独立可执行的，参数与顺序不能换：
 * koreloc 的用法是 `koreloc syms.txt <base_hex> in.ko out.ko`，
 * 其中 base_hex 必须是 perfleak2 / pd2055_exploit 报出来的内核基址。
 *
 * 这里只做「按顺序跑 + 抓输出」，不猜内核结构 —— 换机型或换内核版本时，
 * 只需要替换 assets/iqoo/ 下的二进制和 syms.txt，代码不用动。
 */
class IqooRootRunner(private val context: Context) {

    /** 一步的执行结果。 */
    data class Step(
        val name: String,
        val exitCode: Int,
        val output: String,
        val base: Long? = null,
    )

    /** 整体结果。 */
    data class Result(
        val workDir: File,
        val steps: List<Step>,
        val rootBase: Long?,
        val relocatedKo: File?,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    private val workDir = File(context.filesDir, "iqoo")
    private val assetDir = "iqoo"

    /**
     * 设备判定。
     *
     * PD2055 是 vivo 的型号代码；不同固件里 model 有时带前缀，
     * 所以 model、device、board 三个字段都看一遍。
     */
    fun matchDevice(): Boolean {
        val fields = listOf(
            Build.MODEL, Build.DEVICE, Build.BOARD, Build.PRODUCT,
            runCatching { Build::class.java.getField("ODM_SKU").get(null) as String }.getOrNull().orEmpty(),
        ).joinToString(" ").lowercase()
        return fields.contains("pd2055") ||
            fields.contains("iqoo neo 5") ||
            fields.contains("iqoo_neo5") ||
            fields.contains("neo5")
    }

    /** 把 assets/iqoo 下的文件释放到私有目录，并给可执行权限。 */
    fun stage(): File {
        workDir.mkdirs()
        listOf("perfleak2", "pd2055_exploit", "koreloc", "kernelsu.ko", "syms.txt").forEach { name ->
            val target = File(workDir, name)
            // 每次覆盖，避免残留旧二进制
            context.assets.open("$assetDir/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            // 可执行文件 0755（493），数据文件 0644（420）
            val mode = if (name.endsWith(".txt") || name.endsWith(".ko")) 420 else 493
            runCatching { Os.chmod(target.absolutePath, mode) }
        }
        return workDir
    }

    /**
     * 完整链路。
     *
     * [onStep] 每完成一步就回调一次，方便界面实时显示进度。
     */
    fun run(onStep: (Step) -> Unit = {}): Result {
        val dir = runCatching { stage() }.getOrElse {
            return Result(workDir, emptyList(), null, null, "释放 assets 失败: ${it.message}")
        }

        val steps = mutableListOf<Step>()

        // ① perfleak2：泄漏内核地址
        val leak = exec(File(dir, "perfleak2"), emptyList(), 90)
        steps += leak
        onStep(leak)

        // ② pd2055_exploit：拿 uid 0
        val exploit = exec(File(dir, "pd2055_exploit"), emptyList(), 120)
        steps += exploit
        onStep(exploit)

        // 基址优先从 exploit 输出里找，找不到再退回泄漏那一步
        val base = exploit.base ?: leak.base

        // ③ koreloc：重定位模块
        val relocated = File(dir, "kernelsu.reloc.ko")
        val korelocStep: Step = if (base != null) {
            val s = exec(
                File(dir, "koreloc"),
                listOf("syms.txt", "0x" + base.toString(16), "kernelsu.ko", relocated.name),
                60,
            )
            steps += s
            onStep(s)
            s
        } else {
            Step("koreloc", -1, "没有拿到内核基址，跳过重定位").also {
                steps += it
                onStep(it)
            }
        }

        val ko = if (relocated.isFile && relocated.length() > 0) relocated else null

        // ④ 加载模块。exploit 成功后当前进程通常已经是 uid 0，
        //    所以这里直接问内核要 insmod；失败也不影响 exploit 本身的结果。
        if (ko != null) {
            val load = exec(
                File("/system/bin/insmod"),
                listOf(ko.absolutePath),
                30,
            )
            steps += load
            onStep(load)
        }

        return Result(
            workDir = dir,
            steps = steps,
            rootBase = base,
            relocatedKo = ko,
            error = if (exploit.exitCode != 0) "exploit 返回 ${exploit.exitCode}" else null,
        )
    }

    /**
     * 跑一个二进制并收完整输出。
     *
     * exploit 会 fork 子进程，父进程可能先退出，所以超时给得比较宽，
     * 并且 stdout/stderr 合并读，避免缓冲区互锁。
     */
    private fun exec(
        bin: File,
        args: List<String>,
        timeoutSeconds: Long,
    ): Step {
        if (!bin.isFile || !bin.canExecute()) {
            return Step(bin.name, -1, "文件不存在或不可执行: ${bin.absolutePath}")
        }
        val cmd = mutableListOf(bin.absolutePath).apply { addAll(args) }
        val text = StringBuilder()
        return try {
            val process = ProcessBuilder(cmd)
                .directory(bin.parentFile)
                .redirectErrorStream(true)
                .start()
            // 读取放后台线程，主线程只等进程结束，避免管道写满死锁
            val reader = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(text) { text.append(line).append('\n') }
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            val done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            reader.join(2000)
            val code = if (done) process.exitValue() else -1
            if (!done) runCatching { process.destroyForcibly() }
            val output = synchronized(text) { text.toString() }
            Step(bin.name, code, output.ifBlank { if (done) "(无输出)" else "超时" }, findBase(output))
        } catch (e: Exception) {
            Step(bin.name, -1, "启动失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 从输出里找内核基址。
     *
     * 不同版本的 exploit 打印格式不统一，这里按几种常见形态依次尝试：
     *   base=0xffff8000...
     *   kernel_base: ffff8000...
     *   单独一行 0xffff...（16 位十六进制）
     */
    private fun findBase(output: String): Long? {
        val patterns = listOf(
            Regex("""(?i)(?:kernel[_ ]?base|base|kbase|kaslr[_ ]?base)\s*[:=]\s*(?:0x)?([0-9a-f]{8,16})"""),
            Regex("""0x([0-9a-f]{12,16})"""),
            Regex("""\b(ffff[0-9a-f]{8,12})\b"""),
        )
        for (p in patterns) {
            for (m in p.findAll(output)) {
                val raw = m.groupValues.last()
                val v = raw.toLongOrNull(16) ?: continue
                // 内核地址必须落在 arm64 的高半区
                if (v < 0) return v   // arm64 内核高半区地址作为 Long 是负数
            }
        }
        return null
    }
}
