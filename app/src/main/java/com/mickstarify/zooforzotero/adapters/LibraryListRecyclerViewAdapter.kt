package com.mickstarify.zooforzotero.adapters

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup.MarginLayoutParams
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.LibraryActivity.ItemPages
import com.mickstarify.zooforzotero.LibraryActivity.ItemSelection
import com.mickstarify.zooforzotero.LibraryActivity.ListAppearance
import com.mickstarify.zooforzotero.LibraryActivity.TagColors
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.AttachmentStates
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import kotlin.math.roundToInt

class LibraryListRecyclerViewAdapter(
    var items: ItemPages?,
    val listener: LibraryListInteractionListener
) : RecyclerView.Adapter<LibraryListRecyclerViewAdapter.ItemViewHolder>() {
    /** Which PDFs are on this device or edited here; badges the PDF icon. */
    var attachmentStates = AttachmentStates(emptySet(), emptySet())

    /** The items selected for a bulk action; while there are any, a tap selects. */
    var selection = ItemSelection()

    /** Settings → Appearance; rows are restyled as they are bound again. */
    var appearance = ListAppearance()

    /** A library's tag colours, by group id (My Library: NO_GROUP_ID). */
    var tagColorsOf: (Int) -> TagColors = { TagColors.NONE }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView = view.findViewById<ImageView>(R.id.imageView_library_list_image)
        val textView_title = view.findViewById<TextView>(R.id.TextView_library_list_title)
        val textView_author = view.findViewById<TextView>(R.id.TextView_library_list_author)
        val pdfImage = view.findViewById<ImageButton>(R.id.imageButton_library_list_attachment)
        val layout = view.findViewById<ConstraintLayout>(R.id.constraintLayout_library_list_item)
        /** The appearance this row's views were last set up for. */
        var styledAs: ListAppearance? = null
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ItemViewHolder {
        // Create a new view, which defines the UI of the list item
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.library_screen_list_item, parent, false)

        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ItemViewHolder,
        position: Int
    ) {
        style(holder)
        val item = items?.get(position) ?: return bindPlaceholder(holder)
        bindTitle(holder, item)
        holder.textView_author.visibility = View.VISIBLE
        holder.textView_author.text = appearance.secondLine.of(item)
        val pdfAttachment = item.getPdfAttachment()
        if (pdfAttachment != null) {
            holder.pdfImage.visibility = View.VISIBLE
            val key = pdfAttachment.itemKey
            when (key) {
                in attachmentStates.edited -> {
                    holder.pdfImage.setImageResource(R.drawable.pdf_edited)
                    holder.pdfImage.contentDescription = "PDF, edited here, waiting to sync"
                }
                in attachmentStates.onDevice -> {
                    holder.pdfImage.setImageResource(R.drawable.pdf_on_device)
                    holder.pdfImage.contentDescription = "PDF, on this device"
                }
                else -> {
                    holder.pdfImage.setImageResource(R.drawable.pdf_24dp)
                    holder.pdfImage.contentDescription = "PDF"
                }
            }
            holder.pdfImage.setOnClickListener {
                if (selection.active) return@setOnClickListener listener.onItemSelect(item)
                Log.d("zotero", "Open Attachment ${item.getTitle()}")
                listener.onItemAttachmentOpen(pdfAttachment)
            }
            holder.pdfImage.setOnLongClickListener {
                if (selection.active) listener.onItemSelectRange(item)
                else listener.onItemAttachmentMenu(pdfAttachment)
                true
            }
        } else {
            holder.pdfImage.visibility = View.GONE
        }
        holder.layout.setOnClickListener {
            if (selection.active) return@setOnClickListener listener.onItemSelect(item)
            Log.d("zotero", "Open Item")
            listener.onItemOpen(item)
        }
        holder.layout.setOnLongClickListener {
            // in selection mode a long press selects from the item pressed before, like Shift-click
            if (selection.active) listener.onItemSelectRange(item) else listener.onItemSelect(item)
            true
        }
        val selected = item.itemKey in selection
        holder.layout.setBackgroundResource(
            if (selected) R.color.listSelected else selectableBackground(holder.layout)
        )
        // without type icons the column is there only while selecting, for the ticks
        holder.imageView.visibility = if (appearance.typeIcons || selection.active) View.VISIBLE else View.GONE
        if (selected) {
            holder.imageView.setImageResource(R.drawable.baseline_check_circle_24)
            holder.imageView.contentDescription = "Selected"
            return
        }
        if (!appearance.typeIcons) {
            holder.imageView.setImageResource(R.drawable.baseline_radio_button_unchecked_24)
            holder.imageView.contentDescription = "Not selected"
            return
        }
        holder.imageView.contentDescription = "icon for the item type"

        val iconResource = when (item.itemType) {
            "note" -> {
                R.drawable.note_24dp
            }
            "book" -> {
                R.drawable.book_24dp
            }
            "bookSection" -> {
                R.drawable.book_section_24dp
            }
            "journalArticle" -> {
                R.drawable.journal_article_24dp
            }
            "magazineArticle" -> {
                R.drawable.magazine_article_24dp
            }
            "newspaperArticle" -> {
                R.drawable.newspaper_article_24dp
            }
            "thesis" -> {
                R.drawable.thesis_24dp
            }
            "letter" -> {
                R.drawable.letter_24dp
            }
            "manuscript" -> {
                R.drawable.manuscript_24dp
            }
            "interview" -> {
                R.drawable.interview_24dp
            }
            "film" -> {
                R.drawable.film_24dp
            }
            "artwork" -> {
                R.drawable.artwork_24dp
            }
            "webpage" -> {
                R.drawable.web_page_24dp
            }
            "attachment" -> {
                R.drawable.treeitem_attachment_web_link_2x
            }
            "report" -> {
                R.drawable.report_24dp
            }
            "bill" -> {
                R.drawable.bill_24dp
            }
            "case" -> {
                R.drawable.case_24dp
            }
            "hearing" -> {
                R.drawable.hearing_24dp
            }
            "patent" -> {
                R.drawable.patent_24dp
            }
            "statute" -> {
                R.drawable.statute_24dp
            }
            "email" -> {
                R.drawable.email_24dp
            }
            "map" -> {
                R.drawable.map_24dp
            }
            "blogPost" -> {
                R.drawable.blog_post_24dp
            }
            "instantMessage" -> {
                R.drawable.instant_message_24dp
            }
            "forumPost" -> {
                R.drawable.forum_post_24dp
            }
            "audioRecording" -> {
                R.drawable.audio_recording_24dp
            }
            "presentation" -> {
                R.drawable.presentation_24dp
            }
            "videoRecording" -> {
                R.drawable.video_recording_24dp
            }
            "tvBroadcast" -> {
                R.drawable.tv_broadcast_24dp
            }
            "radioBroadcast" -> {
                R.drawable.radio_broadcast_24dp
            }
            "podcast" -> {
                R.drawable.podcast_24dp
            }
            "computerProgram" -> {
                R.drawable.computer_program_24dp
            }
            "conferencePaper" -> {
                R.drawable.conference_paper_24dp
            }
            "document" -> {
                R.drawable.document_24dp
            }
            "encyclopediaArticle" -> {
                R.drawable.encyclopedia_article_24dp
            }
            "dictionaryEntry" -> {
                R.drawable.dictionary_entry_24dp
            }
            else -> {
                R.drawable.treeitem_2x
            }
        }
        holder.imageView.setImageResource(iconResource)
    }

    /**
     * The title, after the item's coloured tags as dots and its emoji tags, as in Zotero's
     * items list; a screen reader hears the tag names instead of the dots.
     */
    private fun bindTitle(holder: ItemViewHolder, item: Item) {
        val title = item.getTitle()
        val swatches = if (appearance.tagColors) {
            tagColorsOf(item.getGroup()).swatches(item.getTagList())
        } else emptyList()
        if (swatches.isEmpty()) {
            holder.textView_title.text = title
            holder.textView_title.contentDescription = null
            return
        }
        val text = SpannableStringBuilder()
        for (swatch in swatches) {
            if (swatch.emoji != null) {
                text.append(swatch.emoji)
                continue
            }
            val color = runCatching { Color.parseColor(swatch.color) }.getOrNull() ?: continue
            val start = text.length
            text.append("●")
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        holder.textView_title.text = if (text.isEmpty()) title else text.append(' ').append(title)
        holder.textView_title.contentDescription =
            "$title. " + (if (swatches.size == 1) "Tag " else "Tags ") + swatches.joinToString(", ") { it.tag }
    }

    /** Sizes and spacing for [appearance]; the comfortable ones are the layout's. */
    private fun style(holder: ItemViewHolder) {
        if (holder.styledAs == appearance) return
        holder.styledAs = appearance
        val density = holder.layout.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val compact = appearance.compact
        val gap = dp(if (compact) 2 else 6)

        holder.layout.minHeight = dp(if (compact) 40 else 52)
        holder.textView_title.maxLines = if (compact) 1 else 2
        holder.textView_title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15f else 16f)
        holder.textView_author.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
        (holder.textView_title.layoutParams as MarginLayoutParams).topMargin = gap
        (holder.textView_author.layoutParams as MarginLayoutParams).bottomMargin = gap
        (holder.imageView.layoutParams as MarginLayoutParams).apply {
            width = dp(if (compact) 24 else 32)
            height = width
            topMargin = gap
            bottomMargin = gap
        }
        holder.layout.requestLayout()
    }

    /** A row whose item is still being read: blank, and does nothing. */
    private fun bindPlaceholder(holder: ItemViewHolder) {
        holder.textView_title.text = ""
        holder.textView_title.contentDescription = null
        holder.textView_author.text = ""
        holder.pdfImage.visibility = View.GONE
        holder.imageView.setImageResource(R.drawable.treeitem_2x)
        holder.imageView.visibility = if (appearance.typeIcons) View.VISIBLE else View.GONE
        holder.layout.setOnClickListener(null)
        holder.layout.setOnLongClickListener(null)
        holder.layout.setBackgroundResource(selectableBackground(holder.layout))
    }

    private var rippleBackground = 0

    /** The theme's ripple, as the row's layout sets it; restored after a selected row's tint. */
    private fun selectableBackground(view: View): Int {
        if (rippleBackground == 0) {
            val value = TypedValue()
            view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            rippleBackground = value.resourceId
        }
        return rippleBackground
    }

    override fun getItemCount(): Int {
        return items?.size ?: 0
    }
}

interface LibraryListInteractionListener {
    fun onItemOpen(item: Item)
    fun onItemAttachmentOpen(item: Item)
    fun onItemAttachmentMenu(item: Item)

    /** A long press, or a tap while items are selected: selects or unselects [item]. */
    fun onItemSelect(item: Item)
    /** Selects [item] and every item between it and the one pressed before. */
    fun onItemSelectRange(item: Item)
}