package com.maplesu.core.ui.screen.iqoo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * iQOO Neo 5（PD2055）提权入口。
 *
 * 独立 Activity，不接入 Compose 导航 —— 这条链路是设备专用的、
 * 一次性的、需要实时看 log 的，用普通 View 更直接，也避免和
 * Manager 的双主题（Material/Miuix）路由耦合。
 *
 * 入口：设置页 → iQOO Neo 5 提权。
 */
class IqooRootActivity : Activity() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var startButton: Button
    private lateinit var statusView: TextView
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0E1117"))
        }

        root.addView(TextView(this).apply {
            text = "iQOO Neo 5 提权"
            textSize = 22f
            setTextColor(Color.parseColor("#E6EDF7"))
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#8B9BB4"))
            setPadding(0, 16, 0, 0)
        }
        root.addView(statusView)

        startButton = Button(this).apply {
            text = "开始提权"
            setOnClickListener { start() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 32 })

        logView = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#A9C7FF"))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            setPadding(0, 24, 0, 0)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)

        val runner = IqooRootRunner(this)
        statusView.text = buildString {
            append("型号: ").append(android.os.Build.MODEL).append('\n')
            append("设备: ").append(android.os.Build.DEVICE).append('\n')
            append("内核: ").append(System.getProperty("os.version").orEmpty()).append('\n')
            append("指纹: ").append(android.os.Build.FINGERPRINT).append('\n')
            append(if (runner.matchDevice()) "识别: 匹配 iQOO Neo 5" else "识别: 未匹配（仍可尝试）")
        }
    }

    private fun start() {
        startButton.isEnabled = false
        startButton.text = "执行中…"
        logView.text = ""

        worker.execute {
            val runner = IqooRootRunner(this)
            val result = try {
                runner.run { step -> ui.post { appendLog(step) } }
            } catch (t: Throwable) {
                ui.post {
                    log("!! 异常 ${t.javaClass.simpleName}: ${t.message}")
                    finishRun("失败")
                }
                return@execute
            }

            ui.post {
                if (result.error == null) {
                    log("")
                    log("内核 text: " + (result.rootBase?.let { "0x" + it.toString(16) } ?: "未取得"))
                    log("模块已加载: " + if (result.moduleLoaded) "是（/proc/modules 可见）" else "否")
                    log("模块文件: " + (result.relocatedKo?.absolutePath ?: "未生成"))
                    log("工作目录: " + result.workDir.absolutePath)
                    finishRun("完成")
                } else {
                    log("")
                    log("!! " + result.error)
                    log("日志留在 " + result.workDir.absolutePath)
                    finishRun("失败")
                }
            }
        }
    }

    private fun appendLog(step: IqooRootRunner.Step) {
        log("— ${step.name} (exit=${step.exitCode})")
        step.output.lineSequence().forEach { line ->
            if (line.isNotBlank()) log("  $line")
        }
    }

    private fun log(line: String) {
        logView.append(timeFormat.format(Date()) + "  " + line + "\n")
        (logView.parent as? ScrollView)?.post {
            (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun finishRun(text: String) {
        startButton.isEnabled = true
        startButton.text = "重新执行"
        statusView.text = statusView.text.toString().substringBefore("\n--\n") + "\n--\n状态: " + text
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
