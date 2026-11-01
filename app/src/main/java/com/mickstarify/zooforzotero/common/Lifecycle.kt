package com.mickstarify.zooforzotero.common

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Calls [action] with each value of [flow] while this is started (a ViewModel's state or effects). */
fun <T> LifecycleOwner.collectWhileStarted(flow: Flow<T>, action: (T) -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { action(it) }
        }
    }
}
