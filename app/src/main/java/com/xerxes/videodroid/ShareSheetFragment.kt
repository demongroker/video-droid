package com.xerxes.videodroid

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Shown when another app shares a link to VideoDroid: pick quality (and whether
 * to convert for social) before the job is queued, instead of silently using
 * whatever was last saved.
 */
class ShareSheetFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onShareConfirmed(url: String, quality: String, exportOn: Boolean)
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_share, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val url = arguments?.getString(ARG_URL).orEmpty()
        view.findViewById<TextView>(R.id.shareSheetUrl).text = url

        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.shareQualityGroup)
        val export = view.findViewById<MaterialSwitch>(R.id.shareExportSwitch)
        val saved = arguments?.getString(ARG_QUALITY) ?: "1080p"
        group.check(
            when (saved) {
                "4K" -> R.id.shareQ4k
                "720p" -> R.id.shareQ720
                "480p" -> R.id.shareQ480
                else -> R.id.shareQ1080
            },
        )
        export.isChecked = arguments?.getBoolean(ARG_EXPORT, true) ?: true

        view.findViewById<MaterialButton>(R.id.shareCancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.shareConfirm).setOnClickListener {
            val quality = when (group.checkedButtonId) {
                R.id.shareQ4k -> "4K"
                R.id.shareQ720 -> "720p"
                R.id.shareQ480 -> "480p"
                else -> "1080p"
            }
            listener?.onShareConfirmed(url, quality, export.isChecked)
            dismiss()
        }
    }

    companion object {
        const val TAG = "share-sheet"
        private const val ARG_URL = "url"
        private const val ARG_QUALITY = "quality"
        private const val ARG_EXPORT = "export"

        fun newInstance(url: String, quality: String, exportOn: Boolean): ShareSheetFragment =
            ShareSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putString(ARG_QUALITY, quality)
                    putBoolean(ARG_EXPORT, exportOn)
                }
            }
    }
}

/** Convenience: only show the sheet when the host activity implements Listener. */
fun Activity.showShareSheet(url: String, quality: String, exportOn: Boolean) {
    if (this !is ShareSheetFragment.Listener) return
    if (this !is androidx.appcompat.app.AppCompatActivity) return
    if (supportFragmentManager.findFragmentByTag(ShareSheetFragment.TAG) != null) return
    ShareSheetFragment.newInstance(url, quality, exportOn)
        .show(supportFragmentManager, ShareSheetFragment.TAG)
}
