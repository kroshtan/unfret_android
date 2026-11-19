package net.unfret.android

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * The result card, native, the same as on the website and in the extension: the band with its symbol and label, the
 * headline, the same product sold elsewhere (first, with both photos), the findings with their sources, one next
 * step, what was checked, and the "Wrong result?" and objection links.
 */
class ResultView(private val root: View, private val inflater: LayoutInflater) {
    var onBackToPage: () -> Unit = {}
    var onDone: () -> Unit = {}
    var onReport: () -> Unit = {}
    var onObject: () -> Unit = {}

    /** The button under a quick result's suggestion: true runs a deep check of the same page, false gets checks. */
    var onDeepHint: (runDeep: Boolean) -> Unit = {}

    /** The API in use: photos are loaded only from its own thumbnail proxy. */
    var base: String = UnfretApi.PRODUCTION

    /** Loads a thumbnail (off the main thread) and hands it back on the main thread; null when it can't. */
    var loadImage: (url: String, done: (Bitmap?) -> Unit) -> Unit = { _, done -> done(null) }

    private val context = root.context
    private val card: MaterialCardView = root.findViewById(R.id.band_card)
    private val symbol: TextView = root.findViewById(R.id.band_symbol)
    private val label: TextView = root.findViewById(R.id.band_label)
    private val headline: TextView = root.findViewById(R.id.headline)
    private val noCheck: TextView = root.findViewById(R.id.no_check)
    private val sections: LinearLayout = root.findViewById(R.id.sections)
    private val wrongResult: MaterialButton = root.findViewById(R.id.wrong_result)

    init {
        root.findViewById<MaterialButton>(R.id.back_to_page).setOnClickListener { onBackToPage() }
        root.findViewById<MaterialButton>(R.id.done).setOnClickListener { onDone() }
        root.findViewById<MaterialButton>(R.id.object_result).setOnClickListener { onObject() }
        wrongResult.setOnClickListener { onReport() }
        ViewCompat.setAccessibilityHeading(label, true)
    }

    /** Whether "Wrong result?" is offered: a result with a report token, not reported yet. */
    fun setReportable(reportable: Boolean) {
        wrongResult.visibility = if (reportable) View.VISIBLE else View.GONE
    }

    fun show(result: AnalysisResult) {
        val band = result.band
        val (strong, soft) = colours(band)
        card.setCardBackgroundColor(ContextCompat.getColor(context, soft))
        symbol.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, strong))
        symbol.text = band?.symbol ?: "?"
        val verdict = result.verdict
        label.text = when {
            !result.assessed -> context.getString(R.string.not_assessed)
            verdict != null && verdict.label.isNotEmpty() -> verdict.label
            band != null -> context.getString(bandLabel(band))
            else -> context.getString(R.string.result_title)
        }
        headline.text = verdict?.headline?.takeIf { it.isNotEmpty() } ?: result.explanation
        headline.visibility = if (headline.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // What the check cost, as a sentence: right under the headline when no check was used (good news, easy to
        // miss under the reasons); at the end when one was.
        val charge = chargeLine(result)
        noCheck.text = charge
        noCheck.visibility = if (charge != null && result.chargeFree) View.VISIBLE else View.GONE
        setReportable(result.reportToken != null && result.assessed)

        sections.removeAllViews()
        // Part of the check didn't finish, so the verdict may be milder than it should be: first, under the band card.
        verdict?.incomplete?.let { line ->
            val view = TextView(context)
            view.text = line
            view.setTextAppearance(R.style.TextAppearance_Unfret_Small)
            view.setTextColor(ContextCompat.getColor(context, R.color.ink))
            view.setBackgroundColor(ContextCompat.getColor(context, R.color.surface_2))
            view.setPadding(dp(12), dp(8), dp(12), dp(8))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(12)
            sections.addView(view, params)
        }
        val same = verdict?.sameProduct?.takeIf { apiImage(it.image, base) != null }
        if (same != null) addSameProduct(same)
        result.cleanChecks?.let { checks ->
            val clean = TextView(context)
            clean.text = context.resources.getQuantityString(R.plurals.clean_result, checks, checks)
            clean.setTextAppearance(R.style.TextAppearance_Unfret_Title)
            clean.setTextColor(ContextCompat.getColor(context, R.color.good))
            clean.setPadding(0, dp(16), 0, 0)
            sections.addView(clean)
        }
        val reasons = if (result.needsOverallReason) {
            listOf(
                Reason(
                    context.getString(R.string.reason_overall_title),
                    context.getString(R.string.reason_overall_detail),
                    Severity.LOW,
                ),
            )
        } else {
            result.reasonsToShow
        }
        val positives = verdict?.positives.orEmpty()
        if (result.positivesFirst) {
            addPositives(positives)
            addReasons(reasons)
        } else {
            addReasons(reasons)
            addPositives(positives)
        }
        verdict?.nextStep?.let { step ->
            val view = inflater.inflate(R.layout.item_next_step, sections, false)
            view.findViewById<TextView>(R.id.step_text).text = step
            ViewCompat.setAccessibilityHeading(view.findViewById(R.id.step_heading), true)
            sections.addView(view)
        }
        val checklist = verdict?.checklist.orEmpty()
        if (checklist.isNotEmpty()) {
            val checks = verdict?.checksRun?.takeIf { it > 0 }
            val areas = verdict?.checkedAreas
            addHeading(
                when {
                    checks != null && areas != null -> context.getString(
                        R.string.checklist_title_areas,
                        countOf(R.plurals.checks_count, checks),
                        countOf(R.plurals.areas_count, areas),
                    )
                    checks != null -> context.resources.getQuantityString(R.plurals.checklist_title_count, checks, checks)
                    else -> context.getString(R.string.checklist_title)
                },
            )
            for (item in checklist) addCheck(item)
        }
        result.deepHint?.let(::addDeepHint)
        if (charge != null && !result.chargeFree) {
            val paid = TextView(context)
            paid.text = charge
            paid.setTextAppearance(R.style.TextAppearance_Unfret_Small)
            paid.setTextColor(ContextCompat.getColor(context, R.color.muted))
            paid.setPadding(0, dp(16), 0, 0)
            sections.addView(paid)
        }
    }

    /** What the check cost: the server's sentence (with the checks left), or the same words without the count. */
    private fun chargeLine(result: AnalysisResult): String? = result.chargeNote ?: result.charge?.let {
        context.getString(
            when (it) {
                Charge.QUICK -> R.string.charge_quick
                Charge.NOT_ASSESSED -> R.string.charge_not_assessed
                Charge.REPEAT -> R.string.charge_repeat
                Charge.FREE -> R.string.charge_free
                Charge.BOUGHT -> R.string.charge_bought
            },
        )
    }

    private fun countOf(plural: Int, n: Int): String = context.resources.getQuantityString(plural, n, n)

    /** Once, under the quick result: what a deep check would add here, what it costs, one button. */
    private fun addDeepHint(hint: DeepCheck) {
        val view = inflater.inflate(R.layout.item_deep_hint, sections, false)
        val text = view.findViewById<TextView>(R.id.deep_hint_text)
        text.text = hint.text
        if (hint.quiet) text.setTextColor(ContextCompat.getColor(context, R.color.muted))
        val cost = view.findViewById<TextView>(R.id.deep_hint_cost)
        cost.text = hint.cost
        cost.visibility = if (hint.cost.isEmpty()) View.GONE else View.VISIBLE
        val button = view.findViewById<MaterialButton>(R.id.deep_hint_action)
        button.text = hint.actionLabel
        button.setOnClickListener { onDeepHint(hint.runDeep) }
        sections.addView(view)
    }

    private fun addSameProduct(same: SameProduct) {
        val view = inflater.inflate(R.layout.item_same_product, sections, false)
        view.findViewById<TextView>(R.id.same_title).text = same.title
        view.findViewById<TextView>(R.id.same_detail).text = same.detail
        view.findViewById<TextView>(R.id.other_where).text = same.where
        val other = view.findViewById<ImageView>(R.id.other_photo)
        other.contentDescription = context.getString(R.string.same_product_other_photo, same.where)
        photo(other, view.findViewById(R.id.other_photo_box), same.image)
        photo(view.findViewById(R.id.own_photo), view.findViewById(R.id.own_photo_box), same.ownImage)
        sections.addView(view)
    }

    private fun photo(image: ImageView, box: View, url: String?) {
        val allowed = apiImage(url, base)
        if (allowed == null) {
            box.visibility = View.GONE
            return
        }
        loadImage(allowed) { bitmap ->
            if (bitmap == null) box.visibility = View.GONE else image.setImageBitmap(bitmap)
        }
    }

    private fun addReasons(reasons: List<Reason>) {
        if (reasons.isEmpty()) return
        addHeading(context.getString(R.string.reasons_title))
        for (reason in reasons) {
            val colour = when (reason.severity) {
                Severity.HIGH -> R.color.stop
                Severity.MEDIUM -> R.color.care
                Severity.LOW -> R.color.note
            }
            val severity = context.getString(
                when (reason.severity) {
                    Severity.HIGH -> R.string.severity_high
                    Severity.MEDIUM -> R.string.severity_medium
                    Severity.LOW -> R.string.severity_low
                },
            )
            addItem(reason.title, reason.detail, reason.source, colour, "$severity: ${reason.title}. ${reason.detail}")
        }
    }

    private fun addPositives(positives: List<Positive>) {
        if (positives.isEmpty()) return
        addHeading(context.getString(R.string.positives_title))
        for (positive in positives) addItem(positive.title, positive.detail, positive.source, R.color.good, null)
    }

    private fun addHeading(text: String) {
        val heading = inflater.inflate(R.layout.item_heading, sections, false) as TextView
        heading.text = text
        ViewCompat.setAccessibilityHeading(heading, true)
        sections.addView(heading)
    }

    private fun addItem(title: String, detail: String, source: String, colour: Int, description: String?) {
        val item = inflater.inflate(R.layout.item_finding, sections, false)
        item.findViewById<View>(R.id.mark).setBackgroundColor(ContextCompat.getColor(context, colour))
        item.findViewById<TextView>(R.id.title).text = title
        val detailView = item.findViewById<TextView>(R.id.detail)
        detailView.text = detail
        detailView.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        val sourceView = item.findViewById<TextView>(R.id.source)
        if (source.isNotEmpty()) {
            sourceView.text = context.getString(R.string.source_line, source)
            sourceView.visibility = View.VISIBLE
        }
        if (description != null) {
            item.contentDescription = if (source.isEmpty()) description else "$description ${sourceView.text}"
            item.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            item.findViewById<View>(R.id.title).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            detailView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            sourceView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sections.addView(item)
    }

    private fun addCheck(item: CheckItem) {
        val view = inflater.inflate(R.layout.item_check, sections, false)
        val mark = view.findViewById<TextView>(R.id.check_mark)
        val (sign, colour, word) = when (item.status) {
            CheckStatus.CHECKED -> Triple("✓", R.color.good, R.string.check_checked)
            CheckStatus.CONCERN -> Triple("!", R.color.care, R.string.check_concern)
            CheckStatus.NOT_CHECKED -> Triple("–", R.color.muted, R.string.check_not_checked)
        }
        mark.text = sign
        mark.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colour))
        val labelView = view.findViewById<TextView>(R.id.check_label)
        val count = item.checks?.takeIf { it > 0 }?.let { countOf(R.plurals.checks_count, it) }
        labelView.text = if (count != null) context.getString(R.string.check_count, item.label, count) else item.label
        if (item.status == CheckStatus.NOT_CHECKED) labelView.setTextColor(ContextCompat.getColor(context, R.color.muted))
        val note = view.findViewById<TextView>(R.id.check_note)
        if (item.note.isNotEmpty()) {
            note.text = item.note
            note.visibility = View.VISIBLE
        }
        view.contentDescription = listOf(labelView.text.toString(), context.getString(word), item.note)
            .filter { it.isNotEmpty() }
            .joinToString(": ")
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        labelView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        note.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        sections.addView(view)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** The band in words (the website's), for a result without the server's label. */
    private fun bandLabel(band: Band): Int = when (band) {
        Band.RELIABLE -> R.string.band_reliable
        Band.CONCERNS -> R.string.band_concerns
        Band.CAREFUL -> R.string.band_careful
        Band.JUNK_OR_SCAM -> R.string.band_junk_or_scam
    }

    /** The band's strong and soft colours (the website's good / note / care / stop). */
    private fun colours(band: Band?): Pair<Int, Int> = when (band) {
        Band.RELIABLE -> R.color.good to R.color.good_soft
        Band.CONCERNS -> R.color.note to R.color.note_soft
        Band.CAREFUL -> R.color.care to R.color.care_soft
        Band.JUNK_OR_SCAM -> R.color.stop to R.color.stop_soft
        null -> R.color.muted to R.color.surface_2
    }
}
