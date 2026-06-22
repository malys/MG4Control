package com.mg4.control.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_audio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switch = view.findViewById<Switch>(R.id.switch_loudness)

        switch.setOnCheckedChangeListener { _, checked ->
            if (switch.isPressed) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    MG4Hardware.setLoudnessState(if (checked) 2 else 1)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val state = MG4Hardware.getLoudnessState()
            withContext(Dispatchers.Main) {
                if (isAdded && state >= 0) switch.isChecked = (state == 2)
            }
        }
    }
}
