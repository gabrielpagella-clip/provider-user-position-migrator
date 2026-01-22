package com.clipcuenta.yields

import kotlin.random.Random
import kotlin.ranges.until

object Charsets {
    const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
}

object RandomGenerator {
    private val defaultRandom = Random.Default

    fun fromCharset(charset: String, n: Int): String {
        val stringBuilder = kotlin.text.StringBuilder()
        stringBuilder.ensureCapacity(n)

        for (i in 0 until n) {
            stringBuilder.append(charset[defaultRandom.nextInt(charset.length)])
        }
        return stringBuilder.toString()
    }
}
