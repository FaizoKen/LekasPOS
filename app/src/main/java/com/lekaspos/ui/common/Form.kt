package com.lekaspos.ui.common

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.lekaspos.R

/**
 * Builds simple settings/edit forms in code: labelled fields, switches, choices and buttons in
 * a scrolling column. Keeps the many small forms consistent without one XML file each.
 */
class Form(private val ctx: Context) {

    private val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        setPadding(pad, dp(8), pad, dp(24))
    }

    val view: ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        addView(column)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun section(title: CharSequence): TextView = TextView(ctx, null, 0, R.style.Text_Lekas_Section).also {
        it.text = title
        column.addView(it, lp().apply { topMargin = dp(20) })
    }

    fun info(text: CharSequence): TextView = TextView(ctx, null, 0, R.style.Text_Lekas_Caption).also {
        it.text = text
        column.addView(it, lp().apply { topMargin = dp(6) })
    }

    fun text(
        label: CharSequence,
        value: String?,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        hint: CharSequence? = null,
        lines: Int = 1,
    ): EditText {
        label(label)
        return EditText(ctx).also {
            it.setText(value ?: "")
            it.hint = hint
            it.inputType = if (lines > 1) inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE else inputType
            if (lines > 1) {
                it.minLines = lines
                it.maxLines = lines + 3
            } else {
                it.setSingleLine(true)
            }
            it.minHeight = dp(48)
            column.addView(it, lp())
        }
    }

    fun switch(label: CharSequence, checked: Boolean, onChange: ((Boolean) -> Unit)? = null): Switch = Switch(ctx).also {
        it.text = label
        it.isChecked = checked
        it.minHeight = dp(48)
        it.textSize = 16f
        if (onChange != null) it.setOnCheckedChangeListener { _, v -> onChange(v) }
        column.addView(it, lp().apply { topMargin = dp(8) })
    }

    fun choice(label: CharSequence, options: List<CharSequence>, selected: Int, onChange: ((Int) -> Unit)? = null): Spinner {
        label(label)
        return Spinner(ctx).also {
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, options)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            it.adapter = adapter
            it.setSelection(selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
            it.minimumHeight = dp(48)
            if (onChange != null) {
                it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange(position)
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            column.addView(it, lp())
        }
    }

    fun button(label: CharSequence, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(ctx, null, 0, if (primary) R.style.Widget_Lekas_Button_Primary else R.style.Widget_Lekas_Button_Secondary)
        b.text = label
        b.setOnClickListener { onClick() }
        column.addView(b, lp().apply { topMargin = dp(12) })
        return b
    }

    /** A label on the left and a value on the right (reports). */
    fun row(label: CharSequence, value: CharSequence, bold: Boolean = false): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(32)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val l = TextView(ctx, null, 0, R.style.Text_Lekas_Body).apply { text = label }
        val v = TextView(ctx, null, 0, R.style.Text_Lekas_Body).apply {
            text = value
            gravity = android.view.Gravity.END
        }
        if (bold) {
            l.setTypeface(l.typeface, android.graphics.Typeface.BOLD)
            v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
        }
        line.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        column.addView(line, lp())
        return line
    }

    /** Adds any view (e.g. a list of barcodes) to the column. */
    fun add(v: View): View {
        column.addView(v, lp())
        return v
    }

    private fun label(text: CharSequence) {
        val t = TextView(ctx, null, 0, R.style.Text_Lekas_Label)
        t.text = text
        column.addView(t, lp().apply { topMargin = dp(12) })
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
