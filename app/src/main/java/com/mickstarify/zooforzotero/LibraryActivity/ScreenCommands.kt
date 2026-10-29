package com.mickstarify.zooforzotero.LibraryActivity

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Calls from the library screen's logic to whichever activity shows the screen ([V]):
 * run at once while one is started, otherwise queued (between the two activities of a
 * rotation, or while the app is in the background) and run in order once one starts.
 * The logic outlives activities (LibraryViewModel), so it never holds one: a call made
 * into a destroyed activity drew nothing, and a fragment shown after the activity saved
 * its state crashed. Main thread only.
 */
class ScreenCommands<V : Any> {
    private val pending = ArrayDeque<(V) -> Unit>()
    private var view: V? = null

    fun send(command: (V) -> Unit) {
        val v = view
        if (v != null) command(v) else pending.addLast(command)
    }

    /** Runs commands on [view] while [owner] is started. */
    fun attach(owner: LifecycleOwner, view: V) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                this@ScreenCommands.view = view
                // a command may stop the activity (opening another app): the rest wait
                while (this@ScreenCommands.view === view) {
                    val next = pending.removeFirstOrNull() ?: break
                    next(view)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (this@ScreenCommands.view === view) this@ScreenCommands.view = null
            }
        })
    }
}
