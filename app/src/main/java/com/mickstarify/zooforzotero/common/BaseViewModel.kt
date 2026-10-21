package com.mickstarify.zooforzotero.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
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

data class ViewModelProvides<STATE, EFFECT, EVENT>(
    val state: STATE,
    val effect: Flow<EFFECT>,
    val dispatch: (EVENT) -> Unit
) 

@Composable
fun <STATE, EFFECT, EVENT> BaseViewModel<STATE, EFFECT, EVENT>.provides(): ViewModelProvides<STATE, EFFECT, EVENT> {
    val owner = LocalLifecycleOwner.current
    // collects while STARTED; its initial value is read outside composition (lint:
    // StateFlowValueCalledInComposition)
    val currentStateValue by stateFlow.collectAsStateWithLifecycle()

    val lifecycleAwareEffect = remember(effect, owner) {
        effect.flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED)
    }

    return ViewModelProvides(
        state = currentStateValue,
        effect = lifecycleAwareEffect,
        dispatch = dispatchEvent
    )
}