package com.aetherdesk.lumenfall.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.aetherdesk.lumenfall.R
import com.google.android.material.button.MaterialButton

/**
 * Shared "Liquid Glass" facsimile building blocks — every screen in the app
 * (MainActivity, BundleSettingsActivity) draws from this instead of falling
 * back to stock Android widget chrome: flat gray buttons, system
 * AlertDialogs, system Toasts. See docs/ARCHITECTURE.md for why this is
 * translucency + rounded corners rather than real backdrop blur (Android has
 * no public API for that outside Compose + a blur library).
 */
object GlassUi {
    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /** Wraps [inner] in a translucent rounded glass card. */
    fun panel(context: Context, inner: View, bottomMarginDp: Int = 12): View =
        FrameLayout(context).apply {
            setBackgroundResource(R.drawable.glass_panel_bg)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { bottomMargin = dp(context, bottomMarginDp) }
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            addView(inner)
        }

    fun screenTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(context.getColor(R.color.glass_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
    }

    fun sectionHeader(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(context.getColor(R.color.glass_text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, dp(context, 4), 0, dp(context, 10))
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(context.getColor(R.color.glass_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, 0, 0, dp(context, 6))
    }

    /** Filled, accent-tinted button for primary actions. */
    fun primaryButton(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.glass_text_on_accent))
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.glass_accent))
            cornerRadius = dp(context, 14)
            setOnClickListener { onClick() }
        }

    /** Flat, translucent button for secondary/inline actions. */
    fun flatButton(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.glass_accent))
            setBackgroundColor(context.getColor(R.color.glass_field_fill))
            cornerRadius = dp(context, 14)
            setOnClickListener { onClick() }
        }

    fun switchTints(context: Context): Pair<ColorStateList, ColorStateList> {
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(context.getColor(R.color.glass_accent), context.getColor(R.color.glass_track_inactive))
        )
        val thumb = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(context.getColor(R.color.glass_text_primary), context.getColor(R.color.glass_text_secondary))
        )
        return track to thumb
    }

    /** A styled SwitchCompat — NOT Material's MaterialSwitch, whose default
     * style needs Material3 theme attributes this app's Material2 theme
     * doesn't define (renders invisible, then NPEs); see BundleSettingsActivity. */
    fun switchControl(context: Context, checked: Boolean, onChange: (Boolean) -> Unit): SwitchCompat {
        val (track, thumb) = switchTints(context)
        return SwitchCompat(context).apply {
            showText = false
            textOn = ""
            textOff = ""
            isChecked = checked
            trackTintList = track
            thumbTintList = thumb
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
    }

    fun spinnerAdapter(context: Context, items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(context.getColor(R.color.glass_text_primary))
                v.setPadding(0, 0, 0, 0)
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(context.getColor(R.color.glass_text_primary))
                v.setBackgroundResource(R.drawable.glass_popup_item_bg)
                v.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                return v
            }
        }

    fun styleSpinner(context: Context, spinner: Spinner) {
        spinner.setBackgroundResource(R.drawable.glass_field_bg)
        spinner.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        spinner.setPopupBackgroundResource(R.drawable.glass_popup_bg)
    }

    /** Custom confirm dialog replacing AlertDialog's system chrome. */
    fun confirmDialog(
        context: Context,
        title: String,
        message: String? = null,
        confirmText: String,
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 16))
        }
        content.addView(TextView(context).apply {
            text = title
            setTextColor(context.getColor(R.color.glass_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        if (message != null) {
            content.addView(TextView(context).apply {
                text = message
                setTextColor(context.getColor(R.color.glass_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(context, 8), 0, 0)
            })
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 20), 0, 0)
        }
        buttonRow.addView(
            flatButton(context, "Cancel") { dialog.dismiss() }
                .apply { layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(context, 8) } }
        )
        buttonRow.addView(MaterialButton(context).apply {
            text = confirmText
            setTextColor(context.getColor(R.color.glass_text_on_accent))
            backgroundTintList = ColorStateList.valueOf(
                context.getColor(if (destructive) R.color.glass_danger else R.color.glass_accent)
            )
            cornerRadius = dp(context, 14)
            setOnClickListener { onConfirm(); dialog.dismiss() }
        })
        content.addView(buttonRow)

        val panelView = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.glass_popup_bg)
            addView(content)
            layoutParams = FrameLayout.LayoutParams(dp(context, 340), WRAP_CONTENT)
        }
        dialog.setContentView(panelView)
        dialog.show()
    }

    /** Transient message overlay replacing system Toast, styled to match. */
    fun toast(activity: Activity, message: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val card = TextView(activity).apply {
            text = message
            setTextColor(activity.getColor(R.color.glass_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.glass_popup_bg)
            setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12))
        }
        val params = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(activity, 48)
        }
        root.addView(card, params)
        Handler(Looper.getMainLooper()).postDelayed({
            (card.parent as? ViewGroup)?.removeView(card)
        }, 2600)
    }
}
