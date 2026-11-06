package com.alvaro.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Teclado numérico para el mando: se navega con las flechas y OK. */
object PinDialog {
    fun ask(act: Activity, ui: Ui, pin: String, onOk: () -> Unit) {
        val entered = StringBuilder()
        var dialog: AlertDialog? = null
        val title = TextView(act).apply {
            text = "Introduce el PIN"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 30)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val dots = TextView(act).apply {
            setTextColor(Colors.ACCENT)
            setSizePx(ui, 44)
            gravity = Gravity.CENTER
            setPadding(0, ui.px(10), 0, ui.px(14))
        }

        fun refresh() {
            dots.text = (0 until pin.length).joinToString(" ") { if (it < entered.length) "●" else "○" }
        }

        fun press(label: String) {
            when (label) {
                "⌫" -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                "✕" -> dialog?.dismiss()
                else -> if (entered.length < pin.length) entered.append(label)
            }
            refresh()
            if (entered.length == pin.length) {
                if (entered.toString() == pin) {
                    dialog?.dismiss()
                    onOk()
                } else {
                    entered.clear()
                    title.text = "PIN incorrecto. Inténtalo otra vez"
                    refresh()
                }
            }
        }

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(40), ui.px(30), ui.px(40), ui.px(30))
            setBackgroundColor(Colors.SURFACE)
            addView(title)
            addView(dots)
        }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕"))
        var first: android.view.View? = null
        for (r in rows) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in r) {
                val b = makeButton(act, ui, label, false) { press(label) }
                if (first == null) first = b
                line.addView(b, LinearLayout.LayoutParams(ui.px(120), ui.px(84)).apply {
                    leftMargin = ui.px(8)
                    rightMargin = ui.px(8)
                    topMargin = ui.px(8)
                })
            }
            box.addView(line)
        }
        refresh()
        val d = AlertDialog.Builder(act).setView(box).create()
        dialog = d
        d.show()
        first?.requestFocus()
    }
}
