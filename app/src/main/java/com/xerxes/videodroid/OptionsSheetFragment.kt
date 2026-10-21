package com.xerxes.videodroid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton

/**
 * Options bottom sheet. It edits the same values MainActivity persists:
 * quality, export toggle, aspect mode, aspect ratio, trim window.
 *
 * The caller owns the widgets; this fragment only reports the chosen values.
 */
class OptionsSheetFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onOptionsApplied(
            quality: String,
            exportOn: Boolean,
            mode: String,
            ratio: String,
            trimOn: Boolean,
            trimStart: Int,
            trimEnd: Int,
        )
    }

    companion object {
        const val TAG = "OptionsSheet"
        val QUALITIES = arrayOf("4K", "1080p", "720p", "480p")
        val MODES = arrayOf("Original", "Fill", "Fit")
        val RATIOS = arrayOf(
            "Landscape 16:9", "Landscape 4:3", "Landscape 21:9",
            "Portrait 9:16", "Portrait 3:4", "Portrait 4:5",
            "Square 1:1",
        )

        fun newInstance(
            quality: String,
            exportOn: Boolean,
            mode: String,
            ratio: String,
            trimOn: Boolean,
            trimStart: Int,
            trimEnd: Int,
        ) = OptionsSheetFragment().apply {
            arguments = Bundle().apply {
                putString("quality", quality)
                putBoolean("export", exportOn)
                putString("mode", mode)
                putString("ratio", ratio)
                putBoolean("trim", trimOn)
                putInt("trimStart", trimStart)
                putInt("trimEnd", trimEnd)
            }
        }
    }

    private val listener: Listener?
        get() = (parentFragment as? Listener) ?: (activity as? Listener)

    private var ratioGroup: ChipGroup? = null
    private var modeGroup: ChipGroup? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val a = requireArguments()
        val quality = a.getString("quality") ?: "1080p"
        val exportOn = a.getBoolean("export", true)
        val mode = a.getString("mode") ?: "Original"
        val ratio = a.getString("ratio") ?: "Portrait 9:16"
        val trimOn = a.getBoolean("trim", false)

        val qGroup = view.findViewById<ChipGroup>(R.id.optQuality)
        selectChip(qGroup, qualityChipId(quality))

        view.findViewById<MaterialSwitch>(R.id.optExport).isChecked = exportOn

        modeGroup = view.findViewById<ChipGroup>(R.id.optMode)
        selectChip(modeGroup, modeChipId(mode))

        ratioGroup = view.findViewById<ChipGroup>(R.id.optRatio)
        RATIOS.forEach { label ->
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                id = View.generateViewId()
                tag = label
            }
            ratioGroup?.addView(chip)
        }
        selectChip(ratioGroup, RATIOS.indexOf(ratio).coerceAtLeast(0))

        val trimSwitch = view.findViewById<MaterialSwitch>(R.id.optTrim)
        val trimRow = view.findViewById<View>(R.id.optTrimRow)
        val trimStart = view.findViewById<EditText>(R.id.optTrimStart)
        val trimEnd = view.findViewById<EditText>(R.id.optTrimEnd)
        trimSwitch.isChecked = trimOn
        trimRow.visibility = if (trimOn) View.VISIBLE else View.GONE
        trimStart.setText(a.getInt("trimStart", 1).toString())
        trimEnd.setText(a.getInt("trimEnd", 1).toString())
        trimSwitch.setOnCheckedChangeListener { _, on ->
            trimRow.visibility = if (on) View.VISIBLE else View.GONE
        }

        modeGroup?.setOnCheckedStateChangeListener { _, _ ->
            val m = selectedMode(view) ?: "Original"
            ratioGroup?.visibility = if (m == "Original") View.GONE else View.VISIBLE
        }
        ratioGroup?.visibility = if (mode == "Original") View.GONE else View.VISIBLE

        view.findViewById<MaterialButton>(R.id.optReset).setOnClickListener {
            selectChip(qGroup, QUALITIES.indexOf("1080p"))
            view.findViewById<MaterialSwitch>(R.id.optExport).isChecked = true
            selectChip(modeGroup, 0)
            ratioGroup?.visibility = View.GONE
            trimSwitch.isChecked = false
            trimRow.visibility = View.GONE
        }

        view.findViewById<MaterialButton>(R.id.optApply).setOnClickListener {
            val q = selectedQuality(view) ?: "1080p"
            val m = selectedMode(view) ?: "Original"
            val r = selectedRatio(view) ?: ratio
            listener?.onOptionsApplied(
                q,
                view.findViewById<MaterialSwitch>(R.id.optExport).isChecked,
                m,
                r,
                trimSwitch.isChecked,
                FormatOptions.parseTrimSeconds(trimStart.text.toString(), 1),
                FormatOptions.parseTrimSeconds(trimEnd.text.toString(), 1),
            )
            dismiss()
        }
    }

    private fun qualityChipId(q: String): Int = when (q) {
        "4K" -> R.id.optQ4k
        "720p" -> R.id.optQ720
        "480p" -> R.id.optQ480
        else -> R.id.optQ1080
    }

    private fun modeChipId(m: String): Int = when (m) {
        "Fill" -> R.id.optModeFill
        "Fit" -> R.id.optModeFit
        else -> R.id.optModeOriginal
    }

    private fun selectChip(group: ChipGroup?, id: Int) {
        if (group == null) return
        group.check(id)
    }

    private fun selectChip(group: ChipGroup?, index: Int) {
        if (group == null || index < 0 || index >= group.childCount) return
        val child = group.getChildAt(index)
        group.check(child.id)
    }

    private fun labelOf(group: ChipGroup): String? {
        val id = group.checkedChipId
        if (id == View.NO_ID) return null
        val chip = group.findViewById<Chip>(id) ?: return null
        return (chip.tag as? String) ?: chip.text.toString()
    }

    private fun selectedQuality(view: View): String? =
        labelOf(view.findViewById(R.id.optQuality))

    private fun selectedMode(view: View): String? =
        labelOf(view.findViewById(R.id.optMode))

    private fun selectedRatio(view: View): String? =
        labelOf(view.findViewById(R.id.optRatio))
}
