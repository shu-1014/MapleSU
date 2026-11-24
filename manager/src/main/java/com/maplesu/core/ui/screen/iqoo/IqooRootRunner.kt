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
 * 真实用法是从设备包里的 usage 反推出来的，和最初假设的完全不同：
 *
 *   pd2055_exploit <K-text> <module.ko> <log> [full|walk|leak|stamp] [task]
 *   koreloc        syms.txt <base_hex> in.ko out.ko
 *   perfleak2      （无 usage，靠 perf 事件单独泄漏）
 *
 * 所以流程是**两阶段**：
 *
 *   ① pd2055_exploit 0 <ko> <log> leak   → 它自己泄漏内核 text，日志里打出 `_text=0x...`
 *   ② pd2055_exploit 0x<text> <ko> <log> full → 用这个基址打 patch 并把模块挂上去
 *
 * `0` 是 leak 阶段的基址占位符：该阶段还没拿到真地址。
 * 如果 leak 阶段拿不到，退回 perfleak2；两者都拿不到时仍试一次 full，
 * 把判断交给 exploit 自己（它的日志会留在私有目录里）。
 *
 * Android 把 /data/user/0/<pkg>/files 挂成 noexec，所以三个二进制必须
 * 以 lib*.so 形式随 APK 打进 jniLibs，从 nativeLibraryDir 执行。
 * syms.txt / kernelsu.ko 是纯数据，留在 assets 释放到私有目录当参数用。
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
        val moduleLoaded: Boolean = false,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    private val workDir = File(context.filesDir, "iqoo")
    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    /** PD2055 是 vivo 的型号代码；不同固件 model 字段写法不一致，几个都看。 */
    fun matchDevice(): Boolean {
        val fields = listOf(Build.MODEL, Build.DEVICE, Build.BOARD, Build.PRODUCT)
            .joinToString(" ").lowercase()
        return fields.contains("pd2055") || fields.contains("iqoo neo 5") ||
            fields.contains("iqoo_neo5") || fields.contains("neo5")
    }

    /** 释放数据文件（可执行文件在 jniLibs，不需要也不能放这儿）。 */
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

    private fun binary(libName: String): File? =
        File(nativeDir, libName).takeIf { it.isFile }

    /**
     * 完整链路。
     *
     * [onStep] 每完成一步就回调一次，界面实时显示。
     */
    fun run(onStep: (Step) -> Unit = {}): Result {
        val dir = runCatching { stage() }.getOrElse {
            return Result(workDir, emptyList(), null, null, error = "释放数据文件失败: ${it.message}")
        }

        val exploit = binary("libpd2055_exploit.so")
            ?: return Result(workDir, emptyList(), null, null,
                error = "缺少 libpd2055_exploit.so（nativeLibraryDir=${nativeDir.absolutePath}）")

        val ko = File(dir, "kernelsu.ko")
        val log = File(dir, "exploit.log")
        val steps = mutableListOf<Step>()

        // ---------- ① leak 阶段：拿内核 text ----------
        val leakStep = exec(
            exploit,
            listOf("0", ko.absolutePath, log.absolutePath, "leak"),
            dir, 150,
        )
        steps += leakStep
        onStep(leakStep)

        var base = findText(leakStep.output)
            ?: findTextInFile(log)
            ?: leakStep.base

        // ---------- ①b 备选：perfleak2 ----------
        if (base == null) {
            binary("libperfleak2.so")?.let { pf ->
                val pfStep = exec(pf, emptyList(), dir, 90)
                steps += pfStep
                onStep(pfStep)
                base = findText(pfStep.output) ?: findTextInFile(log) ?: pfStep.base
            }
        }

        // ---------- ② full 阶段：打 patch + 挂模块 ----------
        val ktextArg = base?.let { "0x" + it.toString(16) } ?: "0"
        val fullStep = exec(
            exploit,
            listOf(ktextArg, ko.absolutePath, log.absolutePath, "full"),
            dir, 240,
        )
        steps += fullStep
        onStep(fullStep)
        base = base ?: findText(fullStep.output) ?: findTextInFile(log) ?: fullStep.base

        // ---------- ③ 模块是否真的挂上了 ----------
        val loaded = moduleLoaded()

        val err = when {
            loaded -> null
            fullStep.exitCode != 0 -> "exploit full 阶段返回 ${fullStep.exitCode}"
            else -> null
        }

        return Result(
            workDir = dir,
            steps = steps,
            rootBase = base,
            relocatedKo = ko.takeIf { it.isFile },
            moduleLoaded = loaded,
            error = err,
        )
    }

    /** 读 /proc/modules 判断我们的模块有没有真的进内核。 */
    private fun moduleLoaded(): Boolean = runCatching {
        val text = File("/proc/modules").readText()
        text.contains("maplesu_root") || text.contains("kernelsu")
    }.getOrDefault(false)

    /**
     * 跑一个二进制并收完整输出。
     *
     * exploit 会 fork 子进程，父进程可能先退出，所以超时给得比较宽，
     * stdout/stderr 合并读，避免缓冲区互锁。
     */
    private fun exec(
        bin: File,
        args: List<String>,
        workingDir: File,
        timeoutSeconds: Long,
    ): Step {
        if (!bin.isFile) return Step(bin.name, -1, "文件不存在: ${bin.absolutePath}")
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
                        lines.forEach { line -> synchronized(text) { text.append(line).append('\n') } }
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
            Step(bin.name, code, output.ifBlank { if (done) "(无输出)" else "超时" }, findText(output))
        } catch (e: Exception) {
            Step(bin.name, -1, "启动失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 从文本里找内核 text 基址。
     *
     * exploit 的日志格式是 `... : _text=0xffff...`，另外兼容几种常见写法。
     * arm64 高半区地址作为 Long 是负数，用它过滤误匹配。
     */
    private fun findText(output: String): Long? {
        if (output.isBlank()) return null
        val patterns = listOf(
            Regex("""(?i)_text\s*[:=]\s*(?:0x)?([0-9a-f]{8,16})"""),
            Regex("""(?i)(?:kernel[_ ]?text|ktext|kernel[_ ]?base|kbase)\s*[:=]\s*(?:0x)?([0-9a-f]{8,16})"""),
            Regex("""0x([0-9a-f]{12,16})"""),
            Regex("""\b(ffff[0-9a-f]{8,12})\b"""),
        )
        for (p in patterns) {
            for (m in p.findAll(output)) {
                val v = m.groupValues.last().toLongOrNull(16) ?: continue
                if (v < 0) return v
            }
        }
        return null
    }

    private fun findTextInFile(file: File): Long? = runCatching {
        if (!file.isFile || file.length() == 0L) null else findText(file.readText())
    }.getOrNull()
}
