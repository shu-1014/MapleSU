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
 * 关键约束：Android 把 /data/user/0/<pkg>/files 挂成 noexec，
 * 即使 chmod 0755 也会在 execve 时拿到 EACCES(13)。
 * 所以三个可执行文件必须以 lib*.so 形式随 APK 打进 jniLibs，
 * 运行时从 applicationInfo.nativeLibraryDir 执行 —— 那里才是可执行的。
 * syms.txt / kernelsu.ko 是纯数据，留在 assets，释放到私有目录当参数用。
 *
 * koreloc 的用法是 `koreloc syms.txt <base_hex> in.ko out.ko`，
 * 其中 base_hex 必须是 perfleak2 / pd2055_exploit 报出来的内核基址。
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

    /** 可执行文件所在目录（唯一允许 exec 的位置）。 */
    private val nativeDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    /**
     * 设备判定。
     *
     * PD2055 是 vivo 的型号代码；不同固件里 model 有时带前缀，
     * 所以 model、device、board、product 都看一遍。
     */
    fun matchDevice(): Boolean {
        val fields = listOf(
            Build.MODEL, Build.DEVICE, Build.BOARD, Build.PRODUCT,
        ).joinToString(" ").lowercase()
        return fields.contains("pd2055") ||
            fields.contains("iqoo neo 5") ||
            fields.contains("iqoo_neo5") ||
            fields.contains("neo5")
    }

    /**
     * 释放数据文件到私有目录。
     *
     * 只处理 syms.txt 和 kernelsu.ko —— 可执行文件已经在 jniLibs 里，
     * 不需要（也不能）释放到 files，因为那儿不可执行。
     */
    fun stage(): File {
        workDir.mkdirs()
        listOf("syms.txt", "kernelsu.ko").forEach { name ->
            val target = File(workDir, name)
            context.assets.open("iqoo/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            runCatching { Os.chmod(target.absolutePath, 420) } // 0644
        }
        return workDir
    }

    /** 取 jniLibs 里的可执行文件，并确认真的存在。 */
    private fun binary(libName: String): File? {
        val f = File(nativeDir, libName)
        return if (f.isFile) f else null
    }

    /**
     * 完整链路。
     *
     * [onStep] 每完成一步就回调一次，方便界面实时显示进度。
     */
    fun run(onStep: (Step) -> Unit = {}): Result {
        val dir = runCatching { stage() }.getOrElse {
            return Result(workDir, emptyList(), null, null, "释放数据文件失败: ${it.message}")
        }

        val steps = mutableListOf<Step>()

        // 三个二进制缺一不可，先一次性检查，避免跑到一半才报错
        val perfleak = binary("libperfleak2.so")
        val exploit = binary("libpd2055_exploit.so")
        val koreloc = binary("libkoreloc.so")
        val missing = buildList {
            if (perfleak == null) add("libperfleak2.so")
            if (exploit == null) add("libpd2055_exploit.so")
            if (koreloc == null) add("libkoreloc.so")
        }
        if (missing.isNotEmpty()) {
            val step = Step("preflight", -1, "缺少可执行文件: ${missing.joinToString()}（nativeLibraryDir=${nativeDir.absolutePath}）")
            return Result(workDir, listOf(step), null, null, step.output)
        }

        // ① perfleak2：泄漏内核地址
        val leak = exec(perfleak!!, emptyList(), dir, 90)
        steps += leak
        onStep(leak)

        // ② pd2055_exploit：拿 uid 0
        val exp = exec(exploit!!, emptyList(), dir, 120)
        steps += exp
        onStep(exp)

        // 基址优先从 exploit 输出里找，找不到再退回泄漏那一步
        val base = exp.base ?: leak.base

        // ③ koreloc：重定位模块（参数全用绝对路径，因为工作目录是私有目录）
        val syms = File(dir, "syms.txt")
        val inKo = File(dir, "kernelsu.ko")
        val outKo = File(dir, "kernelsu.reloc.ko")
        val relocate: Step = if (base != null) {
            val s = exec(
                koreloc!!,
                listOf(syms.absolutePath, "0x" + base.toString(16), inKo.absolutePath, outKo.absolutePath),
                dir,
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

        val ko = if (outKo.isFile && outKo.length() > 0) outKo else null

        // ④ 加载模块。exploit 成功后当前进程通常已经是 uid 0，
        //    失败也不影响 exploit 本身的结果，所以只记录日志。
        if (ko != null) {
            val insmod = File("/system/bin/insmod")
            val load = exec(insmod, listOf(ko.absolutePath), dir, 30)
            steps += load
            onStep(load)
        }

        return Result(
            workDir = dir,
            steps = steps,
            rootBase = base,
            relocatedKo = ko,
            error = if (exp.exitCode != 0) "exploit 返回 ${exp.exitCode}" else null,
        )
    }

    /**
     * 跑一个可执行文件并收完整输出。
     *
     * exploit 会 fork 子进程，父进程可能先退出，所以超时给得比较宽，
     * 并且 stdout/stderr 合并读，避免缓冲区互锁。
     */
    private fun exec(
        bin: File,
        args: List<String>,
        workingDir: File,
        timeoutSeconds: Long,
    ): Step {
        if (!bin.isFile) {
            return Step(bin.name, -1, "文件不存在: ${bin.absolutePath}")
        }
        val cmd = mutableListOf(bin.absolutePath).apply { addAll(args) }
        val text = StringBuilder()
        return try {
            val process = ProcessBuilder(cmd)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
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
     *   单独一个 0xffff... token
     */
    private fun findBase(output: String): Long? {
        val patterns = listOf(
            Regex("""(?i)(?:kernel[_ ]?base|kbase|kaslr[_ ]?base|base)\s*[:=]\s*(?:0x)?([0-9a-f]{8,16})"""),
            Regex("""0x([0-9a-f]{12,16})"""),
            Regex("""\b(ffff[0-9a-f]{8,12})\b"""),
        )
        for (p in patterns) {
            for (m in p.findAll(output)) {
                val raw = m.groupValues.last()
                val v = raw.toLongOrNull(16) ?: continue
                // arm64 内核高半区地址作为 Long 是负数
                if (v < 0) return v
            }
        }
        return null
    }
}
