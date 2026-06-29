package com.aetherdesk.lumenfall.ui

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aetherdesk.lumenfall.render.PropertyBus
import com.aetherdesk.lumenfall.storage.BundleProperty
import com.aetherdesk.lumenfall.storage.BundleStore
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Per-bundle settings: renders a control for each property from
 * `LivelyProperties.json`, persists the user's choice as an override, and pushes
 * the change to the running wallpaper via [PropertyBus] for a live update.
 */
class BundleSettingsActivity : AppCompatActivity() {

    private val store by lazy { BundleStore(this) }
    private lateinit var bundleId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bundleId = intent.getStringExtra(EXTRA_BUNDLE_ID) ?: return finish()

        val meta = store.get(bundleId)
        val props = store.properties(bundleId)
        val effective = store.effectiveValues(bundleId)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = meta?.title ?: "Settings"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (props.isEmpty()) {
            form.addView(TextView(this).apply {
                text = "This bundle has no configurable properties."
                setTextColor(Color.GRAY)
                setPadding(0, dp(24), 0, 0)
            })
        } else {
            props.forEach { form.addView(controlFor(it, currentOf(it, effective))) }
        }
        root.addView(ScrollView(this).apply {
            addView(form)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        })
        setContentView(root)
    }

    private fun currentOf(p: BundleProperty, effective: JSONObject): Any? =
        if (effective.has(p.key)) effective.get(p.key) else p.defaultValue

    /** Persist the override and nudge the running wallpaper. */
    private fun apply(key: String, value: Any?) {
        store.setOverride(bundleId, key, value)
        PropertyBus.notify(bundleId, key, value)
    }

    private fun controlFor(p: BundleProperty, current: Any?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(4))
        }
        box.addView(TextView(this).apply {
            text = p.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        box.addView(
            when (p.type.lowercase()) {
                "slider" -> sliderControl(p, current)
                "checkbox" -> checkboxControl(p, current)
                "dropdown" -> dropdownControl(p, current)
                "color" -> colorControl(p, current)
                "label" -> TextView(this).apply {
                    text = (current ?: "").toString()
                    setTextColor(Color.GRAY)
                }
                "button" -> Button(this).apply {
                    text = "Run"
                    setOnClickListener { PropertyBus.notify(bundleId, p.key, true) }
                }
                else -> textControl(p, current) // textbox + unknown -> editable text
            }
        )
        return box
    }

    private fun sliderControl(p: BundleProperty, current: Any?): View {
        val min = p.raw.optDouble("min", 0.0)
        val max = p.raw.optDouble("max", 100.0)
        val step = p.raw.optDouble("step", 1.0).let { if (it <= 0.0) 1.0 else it }
        val integral = step % 1.0 == 0.0 && min % 1.0 == 0.0
        val steps = maxOf(1, ((max - min) / step).roundToInt())
        val cur = (current as? Number)?.toDouble() ?: min

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valueLabel = TextView(this).apply {
            minWidth = dp(56)
            gravity = Gravity.END
        }
        fun fmt(v: Double) = if (integral) v.roundToLong().toString() else String.format("%.2f", v)

        val seek = SeekBar(this).apply {
            this.max = steps
            progress = ((cur - min) / step).roundToInt().coerceIn(0, steps)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                    val v = min + prog * step
                    valueLabel.text = fmt(v)
                    if (fromUser) apply(p.key, if (integral) v.roundToLong() else (v * 1000).roundToLong() / 1000.0)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        valueLabel.text = fmt(min + seek.progress * step)
        row.addView(seek)
        row.addView(valueLabel)
        return row
    }

    private fun checkboxControl(p: BundleProperty, current: Any?): View =
        CheckBox(this).apply {
            isChecked = current as? Boolean ?: false
            setOnCheckedChangeListener { _, checked -> apply(p.key, checked) }
        }

    private fun dropdownControl(p: BundleProperty, current: Any?): View {
        val itemsArr = p.raw.optJSONArray("items") ?: return textControl(p, current)
        val items = (0 until itemsArr.length()).map { itemsArr.optString(it) }
        return Spinner(this).apply {
            adapter = ArrayAdapter(
                this@BundleSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items
            )
            var skipFirst = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (skipFirst) { skipFirst = false; return }
                    apply(p.key, position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            setSelection(((current as? Number)?.toInt() ?: 0).coerceIn(0, maxOf(0, items.size - 1)))
        }
    }

    private fun colorControl(p: BundleProperty, current: Any?): View {
        val initial = (current as? String) ?: "#ffffff"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) }
            runCatching { setBackgroundColor(Color.parseColor(initial)) }
        }
        val edit = EditText(this).apply {
            setText(initial)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            afterTextChanged { text ->
                runCatching {
                    val c = Color.parseColor(text)
                    swatch.setBackgroundColor(c)
                    apply(p.key, text)
                }
            }
        }
        row.addView(swatch)
        row.addView(edit)
        return row
    }

    private fun textControl(p: BundleProperty, current: Any?): View =
        EditText(this).apply {
            setText(current?.toString() ?: "")
            afterTextChanged { apply(p.key, it) }
        }

    private fun EditText.afterTextChanged(action: (String) -> Unit) {
        // Add the watcher AFTER setText so opening the screen doesn't write overrides.
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { action(s?.toString() ?: "") }
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_BUNDLE_ID = "bundle_id"
    }
}
