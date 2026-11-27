package com.benwrk.jabjotjai.capture

/** Choosing what to request from what a sensor offers. */
object StreamSettings {

    /**
     * For each setting the sensor offers, the preferred value if it is among the offered ones,
     * otherwise the highest offered value.
     *
     * @throws NoSuchElementException if a setting is offered with no values at all
     */
    fun <K> choose(available: Map<K, Set<Int>>, preferred: Map<K, Int>): Map<K, Int> =
        available.mapValues { (key, offered) -> preferred[key]?.takeIf { it in offered } ?: offered.max() }
}
