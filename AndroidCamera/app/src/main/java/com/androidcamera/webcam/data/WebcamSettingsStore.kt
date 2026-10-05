package com.androidcamera.webcam.data

import com.androidcamera.webcam.model.StreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the live webcam settings shared by every UI
 * surface in the app: the main activity, the fullscreen preview overlay,
 * and any future fullscreen-only activity we may split off.
 *
 * Why this exists
 * ---------------
 * The previous implementation kept the user-facing state scattered across
 * a handful of `var` fields on the Activity (``manualFocusEnabled``,
 * ``flashOn``, ``availableCameras``, …) plus duplicated ``var fullscreen*``
 * mirrors for every spinner / seek bar in the overlay. Switching between
 * the main page and the fullscreen preview therefore had a non-trivial
 * chance of leaving the two views out of sync (e.g. ISO spinner at 800
 * on the main page, ISO spinner still at "Auto" in fullscreen until the
 * user manually refreshed). Every time a new control was added the
 * number of state copies multiplied.
 *
 * The store collapses that into one ``StateFlow<StreamConfig>``: every
 * surface reads the same immutable value, and every setter publishes a
 * new value to the flow. Subscribers (Activities, Fragments, future
 * Composables, …) simply ``collect`` / ``observe`` the flow and the
 * system keeps the two UIs consistent.
 *
 * Persistence
 * -----------
 * The store does NOT touch SharedPreferences on its own: it is an
 * in-memory cache that mirrors the last value the Activity wrote to
 * [PreferencesRepository]. When the Activity mutates a setting it is
 * expected to (1) update the repository so the change survives a
 * process restart, then (2) call into the store so the in-memory state
 * stays in sync. Reading is the reverse: the Activity loads from the
 * repository on ``onCreate`` and pushes the value into the store, then
 * observes the store from then on.
 */
class WebcamSettingsStore(initial: StreamConfig) {

    private val _state = MutableStateFlow(initial)
    /** Immutable view of the current settings. UI code must NEVER cast
     *  this back to mutable: always create a new ``StreamConfig`` with
     *  the field changed and publish via [update]. */
    val state: StateFlow<StreamConfig> = _state.asStateFlow()

    /** Replace the current snapshot with [next]. The caller is expected
     *  to have already persisted [next] to SharedPreferences; the store
     *  itself is a pure in-memory cache. */
    fun update(next: StreamConfig) {
        _state.value = next
    }

    /** Convenience: copy the current snapshot with a transform. The
     *  returned value is what gets published; callers still need to
     *  persist the new value via [PreferencesRepository] if they want
     *  the change to survive a process restart. */
    fun update(transform: (StreamConfig) -> StreamConfig) {
        _state.value = transform(_state.value)
    }

    /** Convenience accessor that returns the current snapshot without
     *  subscribing to the flow. Use this only for one-shot reads —
     *  anything that needs to react to changes should observe
     *  [state].collect { … } instead. */
    fun current(): StreamConfig = _state.value
}
