package com.kamuinet.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.kamuinet.client.R
import com.kamuinet.client.data.AppInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AppSelectionFragment : Fragment() {

    private val viewModel: AppSelectionViewModel by viewModels()
    private lateinit var adapter: AppAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_app_selection, container, false)
        
        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_apps)

        adapter = AppAdapter { app ->
            viewModel.toggleAppSelection(app.packageName)
        }
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.appList.collect { apps ->
                        adapter.submitList(apps)
                    }
                }
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        return view
    }

    private class AppAdapter(private val onAppClicked: (AppInfo) -> Unit) :
        RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        private var appList = emptyList<AppInfo>()

        fun submitList(newList: List<AppInfo>) {
            appList = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_selection, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = appList[position]
            holder.bind(app, onAppClicked)
        }

        override fun getItemCount(): Int = appList.size

        class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.app_icon)
            private val name: TextView = itemView.findViewById(R.id.app_name)
            private val pkg: TextView = itemView.findViewById(R.id.app_package)
            private val checkBox: CheckBox = itemView.findViewById(R.id.app_checkbox)

            fun bind(app: AppInfo, onAppClicked: (AppInfo) -> Unit) {
                icon.setImageDrawable(app.icon)
                name.text = app.name
                pkg.text = app.packageName
                checkBox.isChecked = app.isSelected
                itemView.setOnClickListener { onAppClicked(app) }
            }
        }
    }
}
