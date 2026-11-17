package net.unfret.android

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** The verdict, native: the band with its symbol and label, the headline, then the reasons and good signs. */
class ResultView(private val root: View, private val inflater: LayoutInflater) {
    var onBackToPage: () -> Unit = {}
    var onDone: () -> Unit = {}

    private val context = root.context
    private val card: MaterialCardView = root.findViewById(R.id.band_card)
    private val symbol: TextView = root.findViewById(R.id.band_symbol)
    private val label: TextView = root.findViewById(R.id.band_label)
    private val headline: TextView = root.findViewById(R.id.headline)
    private val noCheck: TextView = root.findViewById(R.id.no_check)
    private val sections: LinearLayout = root.findViewById(R.id.sections)

    init {
        root.findViewById<MaterialButton>(R.id.back_to_page).setOnClickListener { onBackToPage() }
        root.findViewById<MaterialButton>(R.id.done).setOnClickListener { onDone() }
        ViewCompat.setAccessibilityHeading(label, true)
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
        noCheck.visibility = if (result.charged == false || !result.assessed) View.VISIBLE else View.GONE

        sections.removeAllViews()
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
    }

    private fun addReasons(reasons: List<Reason>) {
        if (reasons.isEmpty()) return
        addHeading(R.string.reasons_title)
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
            addItem(reason.title, reason.detail, colour, "$severity: ${reason.title}. ${reason.detail}")
        }
    }

    private fun addPositives(positives: List<Positive>) {
        if (positives.isEmpty()) return
        addHeading(R.string.positives_title)
        for (positive in positives) addItem(positive.title, positive.detail, R.color.good, null)
    }

    private fun addHeading(text: Int) {
        val heading = inflater.inflate(R.layout.item_heading, sections, false) as TextView
        heading.setText(text)
        ViewCompat.setAccessibilityHeading(heading, true)
        sections.addView(heading)
    }

    private fun addItem(title: String, detail: String, colour: Int, description: String?) {
        val item = inflater.inflate(R.layout.item_finding, sections, false)
        item.findViewById<View>(R.id.mark).setBackgroundColor(ContextCompat.getColor(context, colour))
        item.findViewById<TextView>(R.id.title).text = title
        val detailView = item.findViewById<TextView>(R.id.detail)
        detailView.text = detail
        detailView.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        if (description != null) {
            item.contentDescription = description
            item.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            item.findViewById<View>(R.id.title).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            detailView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sections.addView(item)
    }

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
