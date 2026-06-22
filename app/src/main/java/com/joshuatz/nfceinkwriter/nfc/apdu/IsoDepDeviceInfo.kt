package com.joshuatz.nfceinkwriter.nfc.apdu

data class IsoDepDeviceInfo(
    val width: Int,
    val height: Int,
    val bitsPerPixel: Int,
    val rowsPerBlock: Int,
    val serialNumber: String,
    val rotated: Boolean,
    val hflip: Boolean,
    val raw: ByteArray,
) {
    val pixelsPerByte: Int get() = 8 / bitsPerPixel
    val bytesPerRow: Int get() = width / pixelsPerByte
    val fbWidth: Int get() = if (rotated) height else width
    val fbHeight: Int get() = if (rotated) width else height
    val fbBytesPerRow: Int get() = fbWidth / pixelsPerByte
    val fbTotalBytes: Int get() = fbBytesPerRow * fbHeight

    val blockSizes: List<Int>
        get() {
            val maxBlock = 2000
            val sizes = mutableListOf<Int>()
            var remaining = fbTotalBytes
            while (remaining > 0) {
                val size = minOf(remaining, maxBlock)
                sizes.add(size)
                remaining -= size
            }
            return sizes
        }

    val numBlocks: Int get() = blockSizes.size

    val numColors: Int get() = 1 shl bitsPerPixel
}
