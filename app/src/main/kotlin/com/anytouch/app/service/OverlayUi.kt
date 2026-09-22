package com.anytouch.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.anytouch.app.safety.SafetyVerdict
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 服务侧浮层 UI：悬浮停止球 + 高危二次确认面板。
 * 通道为 TYPE_ACCESSIBILITY_OVERLAY（军令 L1：免 SYSTEM_ALERT_WINDOW）。
 * 实现取纯 View 而非 Compose overlay（偏离军令括注，留痕于收口报告）：
 * 服务 overlay 挂 ComposeView 需自建 LifecycleOwner/SavedStateRegistry 脚手架，
 * S1 只要求"可点、可确认"的视觉占位，浮层视觉属 L3 留白，S2 统一换皮。
 */
class OverlayUi(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var stopBall: TextView? = null
    private var confirmPanel: LinearLayout? = null

    private fun overlayParams(notFocusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        if (notFocusable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            // 设备实测：focusable 窗口默认 touch-modal，确认面板挂起时会吞掉面板外全部触点
            // ——停止球形同虚设。NOT_TOUCH_MODAL 让面板只吃自身边界内的触点，球保持可点。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        },
        PixelFormat.TRANSLUCENT,
    )

    /** @return 球可用（已挂上或本就在位）；false=addView 失败——调用侧必须拒执行，无急停手段不得开跑。 */
    fun showStopBall(onStop: () -> Unit): Boolean {
        if (stopBall != null) return true
        val ball = TextView(context).apply {
            text = "■"
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(0xCCB71C1C.toInt())
            setPadding(28, 18, 28, 18)
            isClickable = true
            setOnClickListener { onStop() }
            contentDescription = "anytouch_stop_ball"
        }
        val params = overlayParams(notFocusable = true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24
        }
        return runCatching { windowManager.addView(ball, params) }
            .onSuccess { stopBall = ball }
            .onFailure { Log.w(TAG, "stop ball addView failed: ${it.message}", it) }
            .isSuccess
    }

    fun hideStopBall() {
        stopBall?.let { runCatching { windowManager.removeView(it) } }
        stopBall = null
    }

    /**
     * 挂起直到用户决策。面板出现即由调用侧超时兜底（withTimeoutOrNull），
     * 超时/取消 = 无人确认 = 默认拒绝，与 STAGE-12 fail-closed 口径一致。
     */
    suspend fun awaitSecondConfirm(verdict: SafetyVerdict.RequiresSecondConfirm): Boolean =
        suspendCancellableCoroutine { cont ->
            val message = buildString {
                append("高危操作：").append(verdict.matchedRule.ruleId)
                verdict.allMatches.firstOrNull()?.let { m ->
                    append('\n').append("命中 ").append(m.matchedField.name).append(" 字段")
                }
                append("\n确认执行？")
            }
            val panel = LinearLayout(context)
            panel.orientation = LinearLayout.VERTICAL
            panel.setBackgroundColor(0xF2212121.toInt())
            panel.setPadding(40, 40, 40, 40)
            panel.addView(TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 16f
            })
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(Button(context).apply {
                text = "取消"
                setOnClickListener {
                    // 决策即撤面板：只 resume 不 remove 会让已完成的确认框永久盖在页面上（设备实测复现）
                    removePanel(panel)
                    if (cont.isActive) cont.resume(false)
                }
            }, lp)
            row.addView(Button(context).apply {
                text = "确认执行"
                setOnClickListener {
                    removePanel(panel)
                    if (cont.isActive) cont.resume(true)
                }
            }, lp)
            panel.addView(row)
            val params = overlayParams(notFocusable = false).apply {
                gravity = Gravity.CENTER
            }
            try {
                windowManager.addView(panel, params)
                confirmPanel = panel
                cont.invokeOnCancellation { removePanel(panel) }
            } catch (e: Exception) {
                // fail-closed 必须可归因：没有这行日志，面板挂不上与用户秒拒在回执上同形
                Log.w(TAG, "confirm panel addView failed, fail-closed deny", e)
                if (cont.isActive) cont.resume(false) // 浮层都放不上去，绝无确认可能
            }
        }

    private fun removePanel(panel: LinearLayout) {
        runCatching { windowManager.removeView(panel) }
        if (confirmPanel === panel) confirmPanel = null
    }

    fun dispose() {
        hideStopBall()
        confirmPanel?.let { removePanel(it) }
    }

    private companion object {
        const val TAG = "AnytouchOverlay"
    }
}
