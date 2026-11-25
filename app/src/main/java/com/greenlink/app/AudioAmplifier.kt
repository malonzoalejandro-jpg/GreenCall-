package com.greenlink.app

import android.content.Context
import kotlin.math.roundToInt

object AudioAmplifier {
    fun apply(context: Context, pcm: ByteArray): ByteArray {
        val gain = context.applicationContext
            .getSharedPreferences("greencall", Context.MODE_PRIVATE)
            .getFloat("playback_gain", 1.0f)
            .coerceIn(0.5f, 3.0f)
        if (gain <= 1.001f || pcm.size < 2) return pcm
        val output = pcm.copyOf()
        var i = 0
        while (i + 1 < output.size) {
            val sample = ((output[i + 1].toInt() shl 8) or (output[i].toInt() and 0xff)).toShort().toInt()
            val boosted = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = (boosted and 0xff).toByte()
            output[i + 1] = ((boosted shr 8) and 0xff).toByte()
            i += 2
        }
        return output
    }
}
