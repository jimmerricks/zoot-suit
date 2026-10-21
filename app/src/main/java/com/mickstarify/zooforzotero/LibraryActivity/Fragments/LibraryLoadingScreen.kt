package com.mickstarify.zooforzotero.LibraryActivity.Fragments

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryLoadingScreenViewModel
import com.mickstarify.zooforzotero.R

class LibraryLoadingScreen : Fragment() {

    companion object {
        fun newInstance() = LibraryLoadingScreen()
    }

    private lateinit var viewModel: LibraryLoadingScreenViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.library_loading_screen_fragment, container, false)
    }

    // The display stays on while a library download shows its progress: once it sleeps,
    // Doze cuts the network off within minutes and a first sync of a large library stops.
    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        menu.clear()
        inflater.inflate(R.menu.library_loading_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel =
            ViewModelProvider(requireActivity()).get(LibraryLoadingScreenViewModel::class.java)

        val textView_message = requireView().findViewById<TextView>(R.id.textView_Library_label)
        val progressBar = requireView().findViewById<ProgressBar>(R.id.progressBar_library)

        progressBar.isIndeterminate = true

        viewModel.getLoadingMessage().observe(viewLifecycleOwner) {
            textView_message.text = it
        }
        viewModel.gettotalAmountOfEntries().observe(viewLifecycleOwner) {
            progressBar.max = it
            progressBar.isIndeterminate = false
            viewModel.setLoadingMessage("Downloading 0 of ${viewModel.gettotalAmountOfEntries().value ?: "?"}")
        }
        viewModel.getAmountOfDownloadedEntries().observe(viewLifecycleOwner) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                progressBar.setProgress(it, true)
            } else {
                progressBar.progress = it
            }
            viewModel.setLoadingMessage("Downloading ${it} of ${viewModel.gettotalAmountOfEntries().value ?: "?"}")
        }
    }

}