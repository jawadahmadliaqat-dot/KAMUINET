package com.kamuinet.client.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.kamuinet.client.R

class HelpFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_help, container, false)
        
        val title = view.findViewById<TextView>(R.id.help_title)
        val content = view.findViewById<TextView>(R.id.help_content)
        
        title.text = TranslationManager.tr("help_title")
        content.text = TranslationManager.tr("help_instructions")
        
        return view
    }
}
