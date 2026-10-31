package com.alok.hello

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating helper: a small draggable "H" bubble. Tap it to open a panel at the TOP of the screen.
 *
 * Keyboard rules (this fixes the "keyboard hides buttons / back and home stop working" bug):
 *  - The panel is NOT focusable by default, so it never grabs the keyboard or the back/home keys.
 *  - Only when you tap the text box does it become focusable and show the keyboard.
 *  - Tapping anywhere outside, Send, or the X turns focus off again and hides the keyboard.
 *  - The panel sits at the top, so the keyboard can never cover it.
 */
class Bubble(private val s: HelloService) {

    private val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val imm = s.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val d get() = s.resources.displayMetrics
    private fun dp(v: Int) = (v * d.density).toInt()

    private val icon = TextView(s)
    private val panel = LinearLayout(s)
    private val out = TextView(s)
    private val input = EditText(s)

    private var iconShown = false
    private var panelShown = false

    private val iconLp = WindowManager.LayoutParams(
        dp(52), dp(52),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = d.widthPixels - dp(64)
        y = dp(180)
    }

    private val panelIdleFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    private val panelLp = WindowManager.LayoutParams(
        dp(340), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        panelIdleFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }

    init {
        buildIcon()
        buildPanel()
    }

    private fun buildIcon() {
        icon.text = "H"
        icon.gravity = Gravity.CENTER
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        icon.setTypeface(icon.typeface, android.graphics.Typeface.BOLD)
        icon.setTextColor(Color.WHITE)
        icon.alpha = 0.92f
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#6C5CE7"))
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        icon.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = iconLp.x; startY = iconLp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true
                    if (moved) {
                        iconLp.x = (startX + dx).toInt().coerceIn(0, d.widthPixels - dp(52))
                        iconLp.y = (startY + dy).toInt().coerceIn(0, d.heightPixels - dp(52))
                        safeUpdate(icon, iconLp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
    }

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(14), dp(12), dp(14), dp(12))
        panel.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#F2141722"))
            setStroke(dp(1), Color.parseColor("#6C5CE7"))
        }
        // Tap anywhere outside the panel: stop typing, hide keyboard, give back/home to the real app.
        panel.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) disableTyping()
            false
        }

        val title = TextView(s)
        title.text = "Hello"
        title.setTextColor(Color.parseColor("#A99BFF"))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        panel.addView(title)

        out.text = Commands.HELP
        out.setTextColor(Color.WHITE)
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        out.maxLines = 9
        out.ellipsize = android.text.TextUtils.TruncateAt.END
        out.setPadding(0, dp(6), 0, dp(8))
        panel.addView(out)

        input.hint = "Yahan dabao, phir command likho…"
        input.setHintTextColor(Color.parseColor("#8A8FA3"))
        input.setTextColor(Color.WHITE)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.maxLines = 4
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, _, _ -> run(); true }
        input.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                enableTyping()
                true
            } else false
        }
        panel.addView(input)

        val row = LinearLayout(s)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(btn("Send") { run() })
        row.addView(btn("Scan") { quick("scan") })
        row.addView(btn("Now") { quick("now") })
        row.addView(btn("Stop") { quick("stop") })
        row.addView(btn("✕") { collapse() })
        panel.addView(row)
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(s)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        b.minWidth = 0
        b.minimumWidth = 0
        b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        b.setOnClickListener { onClick() }
        return b
    }

    // ---------- keyboard / focus ----------

    private fun enableTyping() {
        if (!panelShown) return
        panelLp.flags = panelLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        safeUpdate(panel, panelLp)
        input.post {
            input.requestFocus()
            try {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {
            }
        }
    }

    private fun disableTyping() {
        if (!panelShown) return
        try {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {
        }
        input.clearFocus()
        panelLp.flags = panelLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        safeUpdate(panel, panelLp)
    }

    // ---------- commands ----------

    private fun run() {
        val cmd = input.text.toString()
        input.setText("")
        disableTyping()
        out.text = "…"
        Commands.handle(s, cmd) { showResult(it) }
    }

    private fun quick(cmd: String) {
        disableTyping()
        out.text = "…"
        Commands.handle(s, cmd) { showResult(it) }
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    // ---------- show / hide ----------

    fun show() {
        if (iconShown) return
        try {
            wm.addView(icon, iconLp); iconShown = true
        } catch (_: Exception) {
        }
    }

    fun hide() {
        collapse()
        if (iconShown) {
            try {
                wm.removeView(icon)
            } catch (_: Exception) {
            }
            iconShown = false
        }
    }

    private fun toggle() {
        if (panelShown) collapse() else expand()
    }

    fun expand() {
        if (panelShown) return
        val w = minOf(dp(340), d.widthPixels - dp(24))
        panelLp.width = w
        // Top-right, under the status bar: the keyboard comes from the bottom and can never cover this.
        panelLp.x = maxOf(0, d.widthPixels - w - dp(12))
        panelLp.y = dp(48)
        panelLp.flags = panelIdleFlags
        try {
            wm.addView(panel, panelLp); panelShown = true
        } catch (_: Exception) {
        }
    }

    fun collapse() {
        if (!panelShown) return
        try {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } catch (_: Exception) {
        }
        input.clearFocus()
        try {
            wm.removeView(panel)
        } catch (_: Exception) {
        }
        panelShown = false
    }

    fun showResult(text: String) {
        out.text = text
        expand()
    }

    fun setSymbol(sym: String) {
        icon.text = sym
    }
}
