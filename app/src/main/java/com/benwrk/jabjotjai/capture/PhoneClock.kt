package com.benwrk.jabjotjai.capture

import android.os.SystemClock
import com.benwrk.jabjotjai.raw.Stamp

/** The phone's two clocks (`docs/raw-format.md`, "Three clocks"). */
interface PhoneClock {
    fun wallMs(): Long
    fun monoNs(): Long
    fun stamp(): Stamp = Stamp(wallMs = wallMs(), monoNs = monoNs())
}

object SystemPhoneClock : PhoneClock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monoNs(): Long = SystemClock.elapsedRealtimeNanos()
}
