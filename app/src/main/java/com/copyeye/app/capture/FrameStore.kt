package com.copyeye.app.capture

/**
 * Hands one captured frame from the overlay service to the selection activity.
 *
 * A screen capture is several megabytes; putting it in an `Intent` would blow the binder
 * transaction limit. Both ends live in the same process, so a single in-memory slot is the right
 * transport — and it also gives us one obvious place to guarantee the frame is destroyed.
 *
 * The slot holds at most one frame. Storing a new one releases whatever was there, so a frame can
 * never outlive the scan that produced it even if the activity is killed before consuming it.
 */
object FrameStore {

    @Volatile
    private var pending: ScreenFrame? = null

    @Synchronized
    fun put(frame: ScreenFrame) {
        pending?.takeIf { it !== frame }?.release()
        pending = frame
    }

    /** Takes the frame and empties the slot. The caller owns it and must call `release`. */
    @Synchronized
    fun take(): ScreenFrame? {
        val frame = pending
        pending = null
        return frame?.takeUnless { it.isRecycled }
    }

    /** Drops and frees anything held. Called when a scan is cancelled and on service shutdown. */
    @Synchronized
    fun clear() {
        pending?.release()
        pending = null
    }
}
