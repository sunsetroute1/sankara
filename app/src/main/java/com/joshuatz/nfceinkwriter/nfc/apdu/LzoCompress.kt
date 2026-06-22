package com.joshuatz.nfceinkwriter.nfc.apdu

import org.anarres.lzo.LzoCompressor1x_1
import org.anarres.lzo.lzo_uintp

object LzoCompress {
    private val compressor = LzoCompressor1x_1()

    fun compress(input: ByteArray): ByteArray {
        val output = ByteArray(input.size + compressor.getCompressionOverhead(input.size))
        val outLen = lzo_uintp()
        val result = compressor.compress(input, 0, input.size, output, 0, outLen)
        if (result != 0) {
            throw IllegalStateException("LZO compress failed: $result")
        }
        return output.copyOf(outLen.value)
    }
}
