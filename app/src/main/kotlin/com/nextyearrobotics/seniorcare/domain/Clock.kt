package com.nextyearrobotics.seniorcare.domain

interface Clock {
    fun monotonicMs(): Long
    fun epochMs(): Long
}
object SystemClock : Clock {
    override fun monotonicMs() = System.nanoTime() / 1_000_000
    override fun epochMs() = System.currentTimeMillis()
}
