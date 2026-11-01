package com.mickstarify.zooforzotero.common

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

abstract class BaseViewModel<STATE, EFFECT, EVENT> : ViewModel() {

    abstract val stateFlow: StateFlow<STATE>
    
    private val effectChannel = Channel<EFFECT>()
    val effect: Flow<EFFECT> = effectChannel.receiveAsFlow()
    
    protected abstract fun handleEvent(event: EVENT)
    
    val dispatchEvent: (EVENT) -> Unit = { event ->
        handleEvent(event)
    }
    
    protected suspend fun triggerEffect(effect: EFFECT) {
        effectChannel.send(effect)
    }
}
