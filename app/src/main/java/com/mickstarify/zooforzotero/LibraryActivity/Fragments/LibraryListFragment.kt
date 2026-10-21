package com.mickstarify.zooforzotero.LibraryActivity.Fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mickstarify.zooforzotero.LibraryActivity.LibraryActivity
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.adapters.LibraryListInteractionListener
import com.mickstarify.zooforzotero.adapters.LibraryListRecyclerViewAdapter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LibraryListFragment : Fragment(), LibraryListInteractionListener,
    SwipeRefreshLayout.OnRefreshListener {

    companion object {
        fun newInstance() = LibraryListFragment()
    }

    private lateinit var viewModel: LibraryListViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.library_list_fragment, container, false)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.clear()
        inflater.inflate(R.menu.activity_library_actionbar, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel = ViewModelProvider(requireActivity()).get(LibraryListViewModel::class.java)
        val recyclerView = requireView().findViewById<RecyclerView>(R.id.recyclerView)
        val adapter = LibraryListRecyclerViewAdapter(emptyList(), this)

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        viewModel.getItems().observe(viewLifecycleOwner) { entries ->
            Log.d("zotero", "Loading new set of item (${entries.size} length)")
            adapter.items = entries
            adapter.notifyDataSetChanged()

            if (entries.size == 0) {
                showEmptyList()
            } else {
                hideEmptyList()
            }
        }

        val emptyText = requireView().findViewById<TextView>(R.id.textView_Library_label)
        val clearFilter = requireView().findViewById<Button>(R.id.button_clear_filter)
        clearFilter.setOnClickListener { viewModel.onClearFilterRequested() }
        viewModel.getEmptyMessage().observe(viewLifecycleOwner) { message ->
            emptyText.text = message.text
            message.clearLabel?.let { clearFilter.text = it }
            clearFilter.visibility = if (message.clearLabel != null) View.VISIBLE else View.GONE
        }

        viewModel.getAttachmentStates().observe(viewLifecycleOwner) { states ->
            adapter.attachmentStates = states
            adapter.notifyDataSetChanged()
        }

        val swipeRefresh =
            requireView().findViewById<SwipeRefreshLayout>(R.id.library_swipe_refresh)
        swipeRefresh.setOnRefreshListener(this)
        viewModel.getIsShowingLoadingAnimation().observe(viewLifecycleOwner) {
            swipeRefresh.isRefreshing = it
        }

        // keep the list above the navigation bar
        setupWindowInsets()
    }

    override fun onItemOpen(item: Item) {
        viewModel.onItemClicked(item)
    }

    override fun onCollectionOpen(collection: Collection) {
        viewModel.onCollectionClicked(collection)
    }

    override fun onItemAttachmentOpen(item: Item) {
        viewModel.onAttachmentClicked(item)
    }

    override fun onItemAttachmentMenu(item: Item) {
        (requireActivity() as LibraryActivity).showAttachmentMenu(item)
    }

    override fun onRefresh() {
        Log.d("Zotero", "got request for refresh")
        viewModel.onLibraryRefreshRequested()
    }

    fun showEmptyList() {
        val layout =
            requireView().findViewById<ConstraintLayout>(R.id.constraintLayout_library_content)
        layout.visibility = View.VISIBLE
    }

    fun hideEmptyList() {
        val layout =
            requireView().findViewById<ConstraintLayout>(R.id.constraintLayout_library_content)
        layout.visibility = View.GONE
    }
    
    private fun setupWindowInsets() {
        val rootView = requireView().findViewById<ConstraintLayout>(R.id.constraintLayout_library_view)
        
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Only apply bottom padding if there's actually a navigation bar (insets.bottom > 0)
            if (insets.bottom > 0) {
                view.updatePadding(bottom = insets.bottom)
            }
            
            windowInsets
        }
    }
}