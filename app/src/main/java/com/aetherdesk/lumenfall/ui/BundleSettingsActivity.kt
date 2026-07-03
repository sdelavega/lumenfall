package com.aetherdesk.lumenfall.ui

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.aetherdesk.lumenfall.R
import com.aetherdesk.lumenfall.render.PropertyBus
import com.aetherdesk.lumenfall.storage.AppSettingsStore
import com.aetherdesk.lumenfall.storage.BundleProperty
import com.aetherdesk.lumenfall.storage.BundleStore
import com.aetherdesk.lumenfall.web.BundleWebView
import com.aetherdesk.lumenfall.web.ContactedDomains
import com.aetherdesk.lumenfall.web.GeolocationPolicy
import com.aetherdesk.lumenfall.web.PerformanceGovernor
import com.aetherdesk.lumenfall.web.PerformancePolicy
import com.aetherdesk.lumenfall.web.WebViewTimers
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Per-bundle settings: renders a control for each property from
 * `LivelyProperties.json`, persists the user's choice as an override, and pushes
 * the change to the running wallpaper via [PropertyBus] for a live update.
 *
 * Web bundles also get a live preview: an embedded [WebView] running the actual
 * bundle (same sandbox/limits as the wallpaper, via [BundleWebView]), registered
 * on the [PropertyBus] like any engine — so every control change is visible the
 * moment it's made. (The real wallpaper engine is destroyed underneath this
 * fullscreen Activity, so without the preview a live push has no audience.)
 *
 * Layout is responsive: side-by-side (settings left, preview right) in
 * landscape, stacked (preview top, settings bottom) in portrait. The Activity
 * survives rotation itself (see `configChanges` in the manifest) so the
 * preview WebView isn't torn down and reloaded on every turn.
 *
 * A [PerformanceGovernor] watches the live preview for signs a setting has
 * pushed the device past what it can sustain — achieved fps well under the
 * bundle's cap, sustained high CPU, or system memory pressure — and responds
 * by reverting the most recent control change, or easing the preview's frame
 * rate when overload isn't attributable to a specific change.
 */
class BundleSettingsActivity : AppCompatActivity() {

    private val store by lazy { BundleStore(this) }
    private lateinit var bundleId: String
    private var meta: com.aetherdesk.lumenfall.storage.BundleMeta? = null

    private var preview: WebView? = null
    private var previewContainer: View? = null
    private var formScroll: View? = null
    private var content: LinearLayout? = null
    private var governor: PerformanceGovernor? = null

    // Live state for the revert-on-overload flow.
    private val currentValues = mutableMapOf<String, Any?>()
    private val liveControls = mutableMapOf<String, (Any?) -> Unit>()
    private val displayNames = mutableMapOf<String, String>()
    private var pendingChange: PendingChange? = null
    private var currentPreviewFpsCap = BundleWebView.DEFAULT_FPS_CAP

    // Contacted-domains transparency readout (web bundles). The embedded
    // preview (and the home-screen engine, same process) contact domains
    // live, so the label refreshes on a short timer while resumed.
    private var domainsLabel: TextView? = null
    private val domainsHandler = Handler(Looper.getMainLooper())
    private val domainsRefresh = object : Runnable {
        override fun run() {
            updateDomainsLabel()
            domainsHandler.postDelayed(this, DOMAINS_REFRESH_MS)
        }
    }

    private data class PendingChange(val key: String, val previousValue: Any?, val atMs: Long)

    // Geolocation permission request. A live wallpaper runs in the background
    // and can't host a permission dialog, so the per-bundle toggle in this
    // Activity is the only entry point that prompts. The toggle is only shown
    // for web bundles the screener classified clean (not "limited"), so this
    // prompt never fires for a screened-out bundle.
    private var geoToggle: SwitchCompat? = null
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val toggle = geoToggle ?: return@registerForActivityResult
            if (granted) {
                store.setGeolocationEnabled(bundleId, true)
                toast("Geolocation enabled for this bundle — applies next time it loads")
            } else {
                toggle.isChecked = false
                store.setGeolocationEnabled(bundleId, false)
                toast("Location permission denied — geolocation stays off")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bundleId = intent.getStringExtra(EXTRA_BUNDLE_ID) ?: return finish()

        val meta = store.get(bundleId).also { meta = it }
        val props = store.properties(bundleId)
        val effective = store.effectiveValues(bundleId)
        props.forEach { p ->
            displayNames[p.key] = p.displayName
            currentValues[p.key] = currentOf(p, effective)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundResource(R.drawable.glass_backdrop_bg)
        }
        root.addView(TextView(this).apply {
            text = meta?.title ?: "Settings"
            setTextColor(getColor(R.color.glass_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })

        if (meta?.kind == "web" && meta.entryPath != null) {
            previewContainer = buildPreviewContainer(meta.entryPath, effective.toString())
        }

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (props.isEmpty()) {
            form.addView(TextView(this).apply {
                text = "This bundle has no configurable properties."
                setTextColor(getColor(R.color.glass_text_secondary))
                setPadding(0, dp(24), 0, 0)
            })
        } else {
            props.forEach { form.addView(glassPanel(controlFor(it, currentValues[it.key]))) }
        }
        if (meta?.kind == "web") {
            form.addView(buildGeolocationSection(meta))
        }
        if (meta?.kind == "web") {
            // Network transparency, ported from ÆtherDesk: which external
            // domains this bundle has actually reached (allowed requests only;
            // blocked ones never left the device).
            val label = TextView(this).apply {
                setTextColor(getColor(R.color.glass_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            domainsLabel = label
            updateDomainsLabel()
            form.addView(glassPanel(label))
        }
        formScroll = ScrollView(this).apply { addView(form) }

        content = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        root.addView(content)
        setContentView(root)
        relayout()
    }

    /** Flip between side-by-side (landscape) and stacked (portrait) without rebuilding the preview or form. */
    private fun relayout() {
        val c = content ?: return
        val preview = previewContainer
        val form = formScroll ?: return

        (preview?.parent as? ViewGroup)?.removeView(preview)
        (form.parent as? ViewGroup)?.removeView(form)

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            c.orientation = LinearLayout.HORIZONTAL
            // Settings on the left, preview on the right.
            c.addView(form, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = if (preview != null) dp(12) else 0 })
            preview?.let { c.addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
        } else {
            c.orientation = LinearLayout.VERTICAL
            // Preview on top, settings on the bottom.
            preview?.let { c.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(12) }) }
            c.addView(form, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayout()
    }

    private fun currentOf(p: BundleProperty, effective: JSONObject): Any? =
        if (effective.has(p.key)) effective.get(p.key) else p.defaultValue

    /**
     * Persist the override, nudge the running wallpaper/preview, and remember
     * the prior value for revert. A slider drag fires this once per step, not
     * once per gesture — so a same-key change arriving soon after the last one
     * is treated as a continuation of the same interaction: the revert target
     * stays the value from *before the drag started*, only the timestamp
     * (and hence the attribution window) refreshes. Otherwise a revert would
     * only undo the final, imperceptible micro-step.
     */
    private fun apply(key: String, value: Any?) {
        val now = android.os.SystemClock.elapsedRealtime()
        val existing = pendingChange
        val previous = if (existing != null && existing.key == key && now - existing.atMs < COALESCE_GAP_MS) {
            existing.previousValue
        } else {
            currentValues[key]
        }
        pendingChange = PendingChange(key, previous, now)
        currentValues[key] = value
        store.setOverride(bundleId, key, value)
        PropertyBus.notify(bundleId, key, value)
    }

    /** Revert the most recent change if one happened recently. Returns false if there was nothing eligible. */
    private fun revertLastChange(): String? {
        val pending = pendingChange ?: return null
        if (android.os.SystemClock.elapsedRealtime() - pending.atMs > ATTRIBUTION_WINDOW_MS) return null
        pendingChange = null
        currentValues[pending.key] = pending.previousValue
        store.setOverride(bundleId, pending.key, pending.previousValue)
        PropertyBus.notify(bundleId, pending.key, pending.previousValue)
        liveControls[pending.key]?.invoke(pending.previousValue)
        return displayNames[pending.key] ?: pending.key
    }

    private fun respondToOverload(reason: PerformanceGovernor.OverloadReason) {
        val revertedName = revertLastChange()
        if (revertedName != null) {
            toast("Reverted \"$revertedName\" — too demanding for this device")
            return
        }
        val wv = preview
        val reduced = (currentPreviewFpsCap / 2).coerceAtLeast(MIN_EASE_FPS)
        if (wv != null && reduced < currentPreviewFpsCap) {
            currentPreviewFpsCap = reduced
            wv.evaluateJavascript(
                "window.aetherDesk && (window.aetherDesk.system.fpsCap = $reduced);", null
            )
            // Recalibrate the governor's own threshold to the new cap, or it
            // keeps comparing achieved fps against the stale, higher target
            // and ratchets straight down to the floor even at light load.
            governor?.updateFpsCap(reduced)
            toast("Reduced live-preview frame rate to $reduced fps to protect your device")
        } else {
            toast("This bundle may be too demanding for smooth playback on this device")
        }
    }

    /**
     * Per-bundle geolocation opt-in. Shown only for web bundles the import
     * screener classified clean (not "limited") — a screened-out bundle never
     * sees the geolocation surface at all, so the toggle is meaningless there
     * and would only confuse. Default off; turning it on prompts for
     * ACCESS_FINE_LOCATION (the wallpaper service itself can't prompt).
     *
     * "Applies next time a wallpaper loads" — same snapshot semantics as the
     * global Performance settings, because the bridge is wired (or not) at
     * document-start, not retroactively for an already-running bundle.
     */
    private fun buildGeolocationSection(meta: com.aetherdesk.lumenfall.storage.BundleMeta): View {
        val screening = store.screening(bundleId)
        val clean = screening != null && !screening.limited
        if (!clean) {
            // Limited / pre-screening bundle: explain why the option is absent
            // rather than silently omitting it, so a curious user isn't left
            // wondering where the toggle went.
            return glassPanel(TextView(this).apply {
                text = "Geolocation is unavailable for this bundle: it was flagged by " +
                    "import screening (or imported before screening existed), and " +
                    "location access requires a clean screening result."
                setTextColor(getColor(R.color.glass_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }
        val enabled = store.geolocationEnabled(bundleId)
        val toggle = GlassUi.switchControl(this, enabled) { on ->
            if (on) {
                val granted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    store.setGeolocationEnabled(bundleId, true)
                    toast("Geolocation enabled for this bundle — applies next time it loads")
                } else {
                    // Don't persist yet; the launcher's callback decides based
                    // on the actual grant result, and reverts the toggle if
                    // denied. Set the toggle visually on so the user sees their
                    // tap registered; the callback flips it back on denial.
                    requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            } else {
                store.setGeolocationEnabled(bundleId, false)
            }
        }
        geoToggle = toggle
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            addView(TextView(this@BundleSettingsActivity).apply {
                text = "Allow geolocation"
                setTextColor(getColor(R.color.glass_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(TextView(this@BundleSettingsActivity).apply {
                text = "Lets this wallpaper read the device's GPS location via " +
                    "window.aetherDesk.geolocation. Off by default. Applies next " +
                    "time the wallpaper loads."
                setTextColor(getColor(R.color.glass_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(2), 0, 0)
            })
        })
        row.addView(toggle)
        return glassPanel(row)
    }

    /**
     * The live preview: the real bundle in the shared sandboxed WebView with the
     * same screening limits the wallpaper runs under, subscribed to the bus so
     * [apply]'s existing notify drives it, and watched by [PerformanceGovernor].
     * Wrapped in a rounded, clipped glass panel so its corners match the rest of
     * the UI.
     */
    private fun buildPreviewContainer(entryPath: String, effectiveJson: String): View {
        val screening = store.screening(bundleId)
        val limited = screening?.limited ?: true
        val settings = AppSettingsStore(this).get()
        val resolved = PerformancePolicy.resolve(
            this, settings, screening?.fpsCap ?: BundleWebView.DEFAULT_FPS_CAP
        )
        currentPreviewFpsCap = resolved.fpsCap
        val allowGeolocation = GeolocationPolicy.allowed(this, store, bundleId)
        val wv = BundleWebView.create(
            context = this,
            initialProperties = effectiveJson,
            fpsCap = currentPreviewFpsCap,
            networkBudgetPerMinute = if (limited) screening?.networkBudgetPerMinute ?: 5 else null,
            blockExternalNetwork = settings.blockExternalNetwork,
            allowLan = settings.allowLan,
            initiallySuspended = resolved.initiallySuspended,
            bundleId = bundleId,
            allowGeolocation = allowGeolocation,
            onLog = { handlePreviewMessage(it) }
        ).apply { layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) }

        // Timer control is process-global and shared with the (possibly hidden)
        // wallpaper engine — declare our need through the latch, never directly.
        WebViewTimers.setNeedsTimers(this, wv, true)
        wv.loadUrl("${BundleWebView.APP_ORIGIN}/bundles/$bundleId/$entryPath")
        PropertyBus.register(this, bundleId) { key, value ->
            BundleWebView.pushProperty(wv, key, value)
        }
        preview = wv

        governor = PerformanceGovernor(this, currentPreviewFpsCap, ::respondToOverload).also { it.start() }

        return FrameLayout(this).apply {
            setBackgroundResource(R.drawable.glass_panel_bg)
            setPadding(dp(1), dp(1), dp(1), dp(1))
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(wv)
        }
    }

    /** Bridge messages from the preview: performance ticks feed the governor, everything else just logs. */
    private fun handlePreviewMessage(payload: String) {
        if (payload.contains("\"tick\"")) {
            Regex("\"frames\":(\\d+)").find(payload)?.groupValues?.get(1)?.toIntOrNull()?.let {
                governor?.onFrameReport(it)
            }
        } else {
            Log.d(TAG, "preview: $payload")
        }
    }

    override fun onResume() {
        super.onResume()
        preview?.let {
            it.onResume()
            WebViewTimers.setNeedsTimers(this, it, true)
        }
        governor?.start()
        if (domainsLabel != null) domainsHandler.postDelayed(domainsRefresh, DOMAINS_REFRESH_MS)
    }

    override fun onPause() {
        domainsHandler.removeCallbacks(domainsRefresh)
        governor?.stop()
        preview?.let {
            it.onPause()
            WebViewTimers.setNeedsTimers(this, it, false)
        }
        super.onPause()
    }

    private fun updateDomainsLabel() {
        val label = domainsLabel ?: return
        val domains = ContactedDomains.get(bundleId)
        label.text = if (domains.isEmpty()) {
            "No external domains contacted yet."
        } else {
            "Domains contacted: " + domains.sorted().joinToString(", ")
        }
    }

    override fun onDestroy() {
        governor?.stop()
        PropertyBus.unregister(this)
        preview?.let { WebViewTimers.forget(this, it) }
        preview?.destroy()
        preview = null
        super.onDestroy()
    }

    /** Wraps a control's box in a translucent "glass" card, per the ported ÆtherDesk aesthetic. */
    private fun glassPanel(inner: View): View = FrameLayout(this).apply {
        setBackgroundResource(R.drawable.glass_panel_bg)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(inner)
    }

    private fun controlFor(p: BundleProperty, current: Any?): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = p.displayName
            setTextColor(getColor(R.color.glass_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(6))
        })
        box.addView(
            when (p.type.lowercase()) {
                "slider" -> sliderControl(p, current)
                "checkbox" -> switchControl(p, current)
                "dropdown" -> dropdownControl(p, current)
                "color" -> colorControl(p, current)
                "label" -> TextView(this).apply {
                    text = (current ?: "").toString()
                    setTextColor(getColor(R.color.glass_text_secondary))
                }
                "button" -> MaterialButton(this).apply {
                    text = "Run"
                    setBackgroundColor(getColor(R.color.glass_field_fill))
                    setTextColor(getColor(R.color.glass_accent))
                    cornerRadius = dp(14)
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
        val snappedMax = min + steps * step // Slider requires an evenly divisible range
        val cur = ((current as? Number)?.toDouble() ?: min).coerceIn(min, snappedMax)
        fun fmt(v: Double) = if (integral) v.roundToLong().toString() else String.format("%.2f", v)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valueLabel = TextView(this).apply {
            minWidth = dp(52)
            gravity = Gravity.END
            setTextColor(getColor(R.color.glass_text_primary))
            text = fmt(cur)
        }
        val slider = Slider(this).apply {
            valueFrom = min.toFloat()
            valueTo = snappedMax.toFloat()
            stepSize = step.toFloat()
            value = cur.toFloat()
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            trackHeight = dp(6)
            trackActiveTintList = ColorStateList.valueOf(getColor(R.color.glass_accent))
            trackInactiveTintList = ColorStateList.valueOf(getColor(R.color.glass_track_inactive))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.glass_accent))
            haloTintList = ColorStateList.valueOf(getColor(R.color.glass_accent_dim))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addOnChangeListener { _, value, fromUser ->
                valueLabel.text = fmt(value.toDouble())
                if (fromUser) apply(p.key, if (integral) value.roundToLong() else (value * 1000).roundToLong() / 1000.0)
            }
        }
        liveControls[p.key] = { v ->
            val d = ((v as? Number)?.toDouble() ?: min).coerceIn(min, snappedMax)
            slider.value = d.toFloat()
            valueLabel.text = fmt(d)
        }
        row.addView(slider)
        row.addView(valueLabel)
        return row
    }

    private fun switchControl(p: BundleProperty, current: Any?): View {
        val onTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(getColor(R.color.glass_accent), getColor(R.color.glass_track_inactive))
        )
        val thumbTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(getColor(R.color.glass_text_primary), getColor(R.color.glass_text_secondary))
        )
        lateinit var listener: (View, Boolean) -> Unit
        // Plain SwitchCompat, not Material's MaterialSwitch: MaterialSwitch's
        // default style expects Material3 theme attributes this app's
        // Material2 theme doesn't define, which left it invisible (and, with
        // showText unset, crashed with an NPE on a null text layout).
        // SwitchCompat's default style is theme-lineage-appropriate and just
        // as tintable.
        val toggle = SwitchCompat(this).apply {
            showText = false
            textOn = ""
            textOff = ""
            isChecked = current as? Boolean ?: false
            trackTintList = onTint
            thumbTintList = thumbTint
            listener = { _, checked -> apply(p.key, checked) }
            setOnCheckedChangeListener { view, checked -> listener(view, checked) }
        }
        liveControls[p.key] = { v ->
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = v as? Boolean ?: false
            toggle.setOnCheckedChangeListener { view, checked -> listener(view, checked) }
        }
        return toggle
    }

    private fun dropdownControl(p: BundleProperty, current: Any?): View {
        val itemsArr = p.raw.optJSONArray("items") ?: return textControl(p, current)
        val items = (0 until itemsArr.length()).map { itemsArr.optString(it) }
        lateinit var listener: AdapterView.OnItemSelectedListener
        val spinner = Spinner(this).apply {
            adapter = glassSpinnerAdapter(items)
            setBackgroundResource(R.drawable.glass_field_bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setPopupBackgroundResource(R.drawable.glass_popup_bg)
            var skipFirst = true
            listener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (skipFirst) { skipFirst = false; return }
                    apply(p.key, position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            onItemSelectedListener = listener
            setSelection(((current as? Number)?.toInt() ?: 0).coerceIn(0, maxOf(0, items.size - 1)))
        }
        liveControls[p.key] = { v ->
            spinner.onItemSelectedListener = null
            spinner.setSelection(((v as? Number)?.toInt() ?: 0).coerceIn(0, maxOf(0, items.size - 1)))
            spinner.onItemSelectedListener = listener
        }
        return spinner
    }

    private fun glassSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(getColor(R.color.glass_text_primary))
                v.setPadding(0, 0, 0, 0)
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(getColor(R.color.glass_text_primary))
                v.setBackgroundResource(R.drawable.glass_popup_item_bg)
                v.setPadding(dp(16), dp(14), dp(16), dp(14))
                return v
            }
        }

    private fun colorControl(p: BundleProperty, current: Any?): View {
        val initial = (current as? String) ?: "#ffffff"
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            runCatching { setBackgroundColor(Color.parseColor(initial)) }
        }
        val edit = EditText(this).apply {
            setText(initial)
            setTextColor(getColor(R.color.glass_text_primary))
            setBackgroundResource(R.drawable.glass_field_bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        // The picker proper: collapsed until the swatch is tapped, applies live
        // while dragging (same convention as slider drags).
        val picker = HsvColorPickerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(190)).apply { topMargin = dp(12) }
            visibility = View.GONE
            runCatching { setColor(Color.parseColor(initial)) }
        }
        lateinit var watcher: TextWatcher
        picker.onColorChanged = { c ->
            val hex = String.format("#%06x", c and 0xFFFFFF)
            swatch.setBackgroundColor(c)
            edit.setTextSilently(watcher, hex)
            apply(p.key, hex)
        }
        watcher = edit.afterTextChanged { text ->
            runCatching {
                val c = Color.parseColor(text)
                swatch.setBackgroundColor(c)
                picker.setColor(c)
                apply(p.key, text)
            }
        }
        swatch.setOnClickListener {
            picker.visibility = if (picker.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        liveControls[p.key] = { v ->
            val hex = (v as? String) ?: "#ffffff"
            edit.setTextSilently(watcher, hex)
            runCatching {
                val c = Color.parseColor(hex)
                swatch.setBackgroundColor(c)
                picker.setColor(c)
            }
        }
        row.addView(swatch)
        row.addView(edit)
        column.addView(row)
        column.addView(picker)
        return column
    }

    private fun textControl(p: BundleProperty, current: Any?): View {
        val edit = EditText(this).apply {
            setText(current?.toString() ?: "")
            setTextColor(getColor(R.color.glass_text_primary))
            setBackgroundResource(R.drawable.glass_field_bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val watcher = edit.afterTextChanged { apply(p.key, it) }
        liveControls[p.key] = { v -> edit.setTextSilently(watcher, v?.toString() ?: "") }
        return edit
    }

    /** Adds the watcher and returns it so callers can detach/reattach for silent (non-firing) updates. */
    private fun EditText.afterTextChanged(action: (String) -> Unit): TextWatcher {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { action(s?.toString() ?: "") }
        }
        addTextChangedListener(watcher)
        return watcher
    }

    private fun EditText.setTextSilently(watcher: TextWatcher, value: String) {
        removeTextChangedListener(watcher)
        setText(value)
        setSelection(text?.length ?: 0)
        addTextChangedListener(watcher)
    }

    private fun toast(msg: String) = GlassUi.toast(this, msg)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_BUNDLE_ID = "bundle_id"
        private const val TAG = "LumenfallSettings"
        private const val DOMAINS_REFRESH_MS = 2000L
        private const val ATTRIBUTION_WINDOW_MS = 8000L
        private const val COALESCE_GAP_MS = 1500L
        private const val MIN_EASE_FPS = 10
    }
}
