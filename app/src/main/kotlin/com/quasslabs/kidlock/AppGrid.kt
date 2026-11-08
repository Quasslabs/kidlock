package com.quasslabs.kidlock

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** A GridView that shows every row, so it can sit inside a ScrollView. */
class ExpandedGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GridView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 2, MeasureSpec.AT_MOST))
    }
}

/** App icons with their names underneath, like a home screen. [filter] narrows the list by name. */
class AppGridAdapter(private val context: Context, private val all: List<AllowedApp>) : BaseAdapter() {
    private var shown = all

    fun filter(query: String) {
        val q = query.trim()
        shown = all.filter { it.label.contains(q, ignoreCase = true) }
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val density = context.resources.displayMetrics.density
        val cell = convertView as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (8 * density).toInt()
            setPadding(pad, pad * 2, pad, pad)
            addView(ImageView(context), LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt()))
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                // Every cell the same height: GridView sizes all rows from the first cell.
                minLines = 2
                maxLines = 2
                textSize = 13f
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
        }
        val app = shown[position]
        (cell.getChildAt(0) as ImageView).setImageDrawable(
            runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        )
        (cell.getChildAt(1) as TextView).text = app.label
        return cell
    }
}

/** Shows [apps] as an icon grid in a dialog and calls [onPick] with the chosen one. */
fun showAppGridDialog(activity: Activity, title: String, apps: List<AllowedApp>, onPick: (AllowedApp) -> Unit) {
    val adapter = AppGridAdapter(activity, apps)
    val grid = GridView(activity).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = (96 * activity.resources.displayMetrics.density).toInt()
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        this.adapter = adapter
    }
    val dialog = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(grid)
        .setNegativeButton("Cancel", null)
        .create()
    grid.setOnItemClickListener { _, _, position, _ ->
        dialog.dismiss()
        onPick(adapter.getItem(position))
    }
    dialog.show()
}
