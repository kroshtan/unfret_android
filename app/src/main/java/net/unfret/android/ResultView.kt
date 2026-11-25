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
 * The result card, native, the same as on the website and in the extension: the risk level (0-4) with its words and
 * its one-line explanation, the findings behind it with their sources (worst first), or for an older server the band
 * with its symbol and label and the headline; the same product sold elsewhere (first, with both photos), the findings with their sources, one next
 * step, what was checked, and the "Wrong result?" and objection links.
 */
class ResultView(private val root: View, private val inflater: LayoutInflater) {
    var onBackToPage: () -> Unit = {}
    var onDone: () -> Unit = {}
    var onObject: () -> Unit = {}

    /** The button under a quick result's suggestion: run a deep check of the same page, or support Unfret. */
    var onDeepHint: (DeepCheck.Action) -> Unit = {}

    /** This build's: the Google Play build shows no donate button ([Storefront.deepHint]). */
    private val storefront = Storefront.current

    /** The API in use: photos are loaded only from its own thumbnail proxy. */
    var base: String = UnfretApi.PRODUCTION

    /** Loads a thumbnail (off the main thread) and hands it back on the main thread; null when it can't. */
    var loadImage: (url: String, done: (Bitmap?) -> Unit) -> Unit = { _, done -> done(null) }

    private val context = root.context
    private val card: MaterialCardView = root.findViewById(R.id.band_card)
    private val symbol: TextView = root.findViewById(R.id.band_symbol)
    private val label: TextView = root.findViewById(R.id.band_label)
    private val scale: TextView = root.findViewById(R.id.risk_scale)
    private val headline: TextView = root.findViewById(R.id.headline)
    private val noCheck: TextView = root.findViewById(R.id.no_check)
    private val sections: LinearLayout = root.findViewById(R.id.sections)

    init {
        root.findViewById<MaterialButton>(R.id.back_to_page).setOnClickListener { onBackToPage() }
        root.findViewById<MaterialButton>(R.id.done).setOnClickListener { onDone() }
        root.findViewById<MaterialButton>(R.id.object_result).setOnClickListener { onObject() }
        ViewCompat.setAccessibilityHeading(label, true)
        noCheck.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    /** [note]: why this is a quick check when a deep one was asked for (paused, too many, unavailable), with its link. */
    fun show(result: AnalysisResult, note: CharSequence? = null) {
        val band = result.band
        val risk = result.risk
        val (strong, soft) = if (risk != null) levelColours(risk.level) else colours(band)
        card.setCardBackgroundColor(ContextCompat.getColor(context, soft))
        symbol.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, strong))
        symbol.text = risk?.level?.toString() ?: band?.symbol ?: "?"
        val verdict = result.verdict
        label.text = when {
            !result.assessed -> context.getString(R.string.not_assessed)
            risk != null -> risk.label.ifEmpty { context.getString(levelLabel(risk.level)) }
            verdict != null && verdict.label.isNotEmpty() -> verdict.label
            band != null -> context.getString(bandLabel(band))
            else -> context.getString(R.string.result_title)
        }
        // "Risk level 2 of 4" above the words, so the number in the circle reads on its own.
        scale.visibility = if (risk != null) View.VISIBLE else View.GONE
        if (risk != null) {
            scale.text = context.getString(R.string.risk_scale, risk.level, Risk.MAX)
            label.contentDescription = "${scale.text}: ${label.text}"
        } else {
            label.contentDescription = null
        }
        headline.text = risk?.explanation?.takeIf { it.isNotEmpty() }
            ?: verdict?.headline?.takeIf { it.isNotEmpty() }
            ?: result.explanation
        headline.visibility = if (headline.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // Right under the headline: a quick check that was asked for as a deep one says why ([note]); otherwise what
        // the check cost, as a sentence ("Deep checks are free.").
        noCheck.text = note ?: chargeLine(result)
        noCheck.visibility = if (noCheck.text.isNullOrEmpty()) View.GONE else View.VISIBLE

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
        val findings = result.riskFindings
        val reasons = if (findings.isNotEmpty()) {
            emptyList()
        } else if (result.needsOverallReason) {
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
            addRiskFindings(findings)
            addReasons(reasons)
        } else {
            addRiskFindings(findings)
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
        // The Google Play build: never a donate button.
        storefront.deepHint(result.deepHint)?.let(::addDeepHint)
    }

    /** What the check cost: the server's sentence, or the app's own words (also in the Play build for one about money). */
    private fun chargeLine(result: AnalysisResult): String? = storefront.serverWords(result.chargeNote) ?: result.charge?.let {
        context.getString(
            when (it) {
                Charge.QUICK -> R.string.charge_quick
                Charge.NOT_ASSESSED -> R.string.charge_not_assessed
                Charge.REPEAT -> R.string.charge_repeat
                Charge.FREE -> R.string.charge_free
            },
        )
    }

    private fun countOf(plural: Int, n: Int): String = context.resources.getQuantityString(plural, n, n)

    /** Once, under the quick result: what a deep check would add here, a line about it, at most one button. */
    private fun addDeepHint(hint: DeepCheck) {
        val view = inflater.inflate(R.layout.item_deep_hint, sections, false)
        val text = view.findViewById<TextView>(R.id.deep_hint_text)
        text.text = hint.text
        if (hint.quiet) text.setTextColor(ContextCompat.getColor(context, R.color.muted))
        val cost = view.findViewById<TextView>(R.id.deep_hint_cost)
        cost.text = hint.cost
        cost.visibility = if (hint.cost.isEmpty()) View.GONE else View.VISIBLE
        val button = view.findViewById<MaterialButton>(R.id.deep_hint_action)
        if (hint.action == DeepCheck.Action.NONE || hint.actionLabel.isEmpty()) {
            button.visibility = View.GONE
        } else {
            button.text = hint.actionLabel
            button.setOnClickListener { onDeepHint(hint.action) }
        }
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

    /** The findings behind the risk level, worst first, each marked in its level's colour. */
    private fun addRiskFindings(findings: List<RiskFinding>) {
        if (findings.isEmpty()) return
        addHeading(context.getString(R.string.risk_because_title))
        for (finding in findings) {
            val level = finding.level
            val colour = if (level == null) R.color.muted else levelColours(level).first
            val said = listOf(finding.title, finding.detail).filter { it.isNotEmpty() }.joinToString(". ")
            val description = if (level == null) said else context.getString(R.string.risk_finding_described, level, said)
            addItem(finding.title, finding.detail, finding.source, colour, description)
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

    /** The level in words (the server's), for a risk level without its label. */
    private fun levelLabel(level: Int): Int = when (level) {
        0 -> R.string.level_none
        1 -> R.string.level_manipulation
        2 -> R.string.level_overpaying
        3 -> R.string.level_doesnt_work
        else -> R.string.level_wont_arrive
    }

    /** A level's strong and soft colours: higher is worse. */
    private fun levelColours(level: Int): Pair<Int, Int> = when (level) {
        0 -> R.color.good to R.color.good_soft
        1 -> R.color.note to R.color.note_soft
        2 -> R.color.care to R.color.care_soft
        else -> R.color.stop to R.color.stop_soft
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
