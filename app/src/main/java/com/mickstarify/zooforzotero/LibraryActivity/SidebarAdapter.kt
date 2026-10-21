package com.mickstarify.zooforzotero.LibraryActivity

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo

/**
 * The library screen's sidebar. It replaces a NavigationView menu, which can't nest
 * collections more than one level deep or indent them. Rows come from [SidebarRows].
 */
class SidebarAdapter(private val listener: Listener) :
    ListAdapter<SidebarRow, RecyclerView.ViewHolder>(DIFF) {

    interface Listener {
        fun onLibrarySelected(groupId: Int)
        fun onGroupsToggled()
        fun onCollectionSelected(key: String)
        fun onCollectionToggled(key: String)
        fun onViewSelected(view: SidebarView)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is SidebarRow.Heading -> TYPE_HEADING
        is SidebarRow.Divider -> TYPE_DIVIDER
        else -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADING -> HeadingHolder(inflater.inflate(R.layout.sidebar_heading, parent, false))
            TYPE_DIVIDER -> object : RecyclerView.ViewHolder(
                inflater.inflate(R.layout.sidebar_divider, parent, false)
            ) {}
            else -> RowHolder(inflater.inflate(R.layout.sidebar_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SidebarRow.Heading -> (holder as HeadingHolder).bind(row)
            is SidebarRow.Library -> (holder as RowHolder).bind(row)
            is SidebarRow.CollectionEntry -> (holder as RowHolder).bind(row)
            is SidebarRow.View -> (holder as RowHolder).bind(row)
            is SidebarRow.Divider -> {}
        }
    }

    private inner class HeadingHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.sidebar_heading_title)
        private val chevron = view.findViewById<ImageView>(R.id.sidebar_heading_chevron)

        fun bind(row: SidebarRow.Heading) {
            title.text = row.title
            chevron.visibility = if (row.expanded == null) View.GONE else View.VISIBLE
            chevron.rotation = if (row.expanded == true) 90f else 0f
            if (row.expanded == null) {
                itemView.setOnClickListener(null)
                itemView.isClickable = false
                itemView.background = null
            } else {
                itemView.setBackgroundResource(selectableBackground(itemView))
                itemView.setOnClickListener { listener.onGroupsToggled() }
                itemView.contentDescription =
                    "${row.title}, " + if (row.expanded) "expanded" else "collapsed"
            }
        }
    }

    private inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val chevron = view.findViewById<ImageView>(R.id.sidebar_chevron)
        private val icon = view.findViewById<ImageView>(R.id.sidebar_icon)
        private val title = view.findViewById<TextView>(R.id.sidebar_title)
        private val density = view.resources.displayMetrics.density
        private val normalTextColors = title.textColors

        private fun show(
            text: String, iconRes: Int, selected: Boolean, bold: Boolean, depth: Int = 0,
            onClick: () -> Unit
        ) {
            title.text = text
            icon.setImageResource(iconRes)
            itemView.isActivated = selected
            val highlight = selected || bold
            val primary = ContextCompat.getColor(itemView.context, R.color.colorPrimary)
            if (highlight) title.setTextColor(primary) else title.setTextColor(normalTextColors)
            title.setTypeface(null, if (highlight) Typeface.BOLD else Typeface.NORMAL)
            ImageViewCompat.setImageTintList(
                icon, if (highlight) ColorStateList.valueOf(primary) else normalTextColors
            )
            // a collection's chevron column is 40dp; other rows have none and start at 16dp.
            // 16dp more per level of the tree.
            val start = if (chevron.visibility == View.GONE) 16 else depth * 16
            itemView.setPaddingRelative((start * density).toInt(), 0, itemView.paddingEnd, 0)
            itemView.setOnClickListener { onClick() }
        }

        fun bind(row: SidebarRow.Library) {
            chevron.visibility = View.GONE
            chevron.setOnClickListener(null)
            show(
                row.name,
                if (row.groupId == GroupInfo.NO_GROUP_ID) R.drawable.baseline_library_books_24 else R.drawable.baseline_group_24,
                selected = row.isSelected, bold = row.isActive
            ) { listener.onLibrarySelected(row.groupId) }
        }

        fun bind(row: SidebarRow.CollectionEntry) {
            chevron.visibility = if (row.hasChildren) View.VISIBLE else View.INVISIBLE
            show(row.name, R.drawable.ic_folder_black_24dp, row.isSelected, bold = false, depth = row.depth) {
                listener.onCollectionSelected(row.key)
            }
            if (row.hasChildren) {
                chevron.rotation = if (row.isExpanded) 90f else 0f
                chevron.contentDescription = (if (row.isExpanded) "Collapse " else "Expand ") + row.name
                chevron.setOnClickListener { listener.onCollectionToggled(row.key) }
            } else {
                chevron.setOnClickListener(null)
            }
        }

        fun bind(row: SidebarRow.View) {
            chevron.visibility = View.GONE
            chevron.setOnClickListener(null)
            val iconRes = when (row.view) {
                SidebarView.UNFILED -> R.drawable.baseline_description_24
                SidebarView.MY_PUBLICATIONS -> R.drawable.baseline_book_24
                SidebarView.TRASH -> R.drawable.baseline_delete_24
            }
            show(row.view.title, iconRes, row.isSelected, bold = false) {
                listener.onViewSelected(row.view)
            }
        }
    }

    private fun selectableBackground(view: View): Int {
        val value = android.util.TypedValue()
        view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SidebarRow>() {
            override fun areItemsTheSame(old: SidebarRow, new: SidebarRow) = old.id == new.id
            override fun areContentsTheSame(old: SidebarRow, new: SidebarRow) = old == new
        }

        private const val TYPE_ROW = 0
        private const val TYPE_HEADING = 1
        private const val TYPE_DIVIDER = 2
    }
}
