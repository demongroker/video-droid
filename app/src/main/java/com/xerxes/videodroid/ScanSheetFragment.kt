package com.xerxes.videodroid

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * "Videos on this page" — the picker for [PageScanner] results.
 *
 * Each row is one candidate: host, type, size and duration when known, with a checkbox. The
 * best-ranked candidate is preselected. Nothing is downloaded until a row is confirmed, and any
 * stream that looked encrypted is shown greyed out with a plain message instead of a checkbox.
 */
class ScanSheetFragment : BottomSheetDialogFragment() {

    interface Listener {
        /** Called with the URLs the owner chose (already duplicate-checked by the caller). */
        fun onScanPicked(urls: List<String>)
    }

    private var listener: Listener? = null
    private var candidates: List<PageScanner.Candidate> = emptyList()
    private var ranked: List<PageScanner.Candidate> = emptyList()
    private val checked = LinkedHashSet<String>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SecureScreen.apply(this)

        val rows = view.findViewById<LinearLayout>(R.id.scanRows)
        val hint = view.findViewById<TextView>(R.id.scanHint)
        val go = view.findViewById<android.widget.Button>(R.id.scanDownload)

        val usable = candidates.filterNot { it.drm }
        if (usable.isEmpty()) {
            hint.text = "This video is protected and can't be saved"
            go.visibility = View.GONE
            return
        }

        hint.text = if (usable.size == 1) "1 video found" else "${usable.size} videos found"
        // Best first: page's own host, then the biggest, then the longest.
        ranked = usable.sortedWith(
            compareByDescending<PageScanner.Candidate> { it.sameHostAsPage }
                .thenByDescending { it.sizeBytes }
                .thenByDescending { it.durationSeconds },
        )
        val bestUrl = CandidatePicker.choose(
            usable.map {
                CandidatePicker.Candidate(
                    url = it.url,
                    sizeBytes = it.sizeBytes,
                    durationSeconds = it.durationSeconds,
                    sameHostAsPage = it.sameHostAsPage,
                )
            },
        )?.url
        bestUrl?.let { checked.add(it) }

        for (c in ranked) {
            rows.addView(buildRow(c))
        }

        go.setOnClickListener {
            val picked = checked.toList()
            if (picked.isEmpty()) return@setOnClickListener
            Haptics.tick(requireContext())
            listener?.onScanPicked(picked)
            dismissAllowingStateLoss()
        }
    }

    private fun buildRow(c: PageScanner.Candidate): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        if (c.drm) {
            val label = TextView(ctx).apply {
                text = "Protected · ${PageScanner.hostOf(c.url)} — this video can't be saved"
                setTextColor(0xFF9A9AAB.toInt())
                textSize = 12f
            }
            row.addView(label)
            return row
        }

        val box = CheckBox(ctx).apply {
            isChecked = checked.contains(c.url)
            setOnCheckedChangeListener { _, on ->
                if (on) checked.add(c.url) else checked.remove(c.url)
            }
            contentDescription = "Select ${PageScanner.hostOf(c.url)}"
        }
        row.addView(box)

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(ctx).apply {
            text = describe(c)
            setTextColor(0xFFEDEDF2.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val sub = TextView(ctx).apply {
            text = "${PageScanner.hostOf(c.url)} · ${c.kind.name}" +
                if (c.sameHostAsPage) " · this site" else ""
            setTextColor(0xFF9A9AAB.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        column.addView(title)
        column.addView(sub)
        row.addView(
            column,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        return row
    }

    private fun describe(c: PageScanner.Candidate): String {
        val parts = ArrayList<String>()
        parts.add(
            when (c.kind) {
                PageScanner.Kind.HLS -> "Stream (HLS)"
                PageScanner.Kind.DASH -> "Stream (DASH)"
                PageScanner.Kind.WEBM -> "WebM"
                PageScanner.Kind.MP4 -> "MP4"
                PageScanner.Kind.IFRAME -> "Embedded player"
                PageScanner.Kind.UNKNOWN -> "Media"
            },
        )
        if (c.sizeBytes > 0) parts.add(LibraryFormat.size(c.sizeBytes))
        if (c.durationSeconds > 0) parts.add(LibraryFormat.duration((c.durationSeconds * 1000).toLong()))
        if (c.title.isNotBlank()) parts.add(c.title.take(40))
        return parts.joinToString(" · ")
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "ScanSheet"

        fun newInstance(candidates: List<PageScanner.Candidate>): ScanSheetFragment =
            ScanSheetFragment().apply { this.candidates = candidates }
    }
}
