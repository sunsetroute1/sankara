package com.joshuatz.nfceinkwriter.nfc.apdu

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log

object IsoDepEInkProtocol {
    private const val TAG = "IsoDepEInk"
    private const val MAX_FRAGMENT = 250

    private val COLOR_MODE_TO_BPP = mapOf(
        0x01 to 1,
        0x07 to 2,
        0x47 to 1,
    )

    private val ROTATED_PANELS = setOf(296 to 128)

    fun readDeviceInfo(isoDep: IsoDep): IsoDepDeviceInfo {
        val raw = transceive(isoDep, 0x00, 0xD1, 0x00, 0x00, null, le = 256)
        return parseDeviceInfo(raw)
    }

    /** Read-only probe for discovery mode — connects, authenticates, reads device info. */
    fun probeDeviceInfo(tag: Tag): IsoDepDeviceInfo? {
        val isoDep = IsoDep.get(tag) ?: return null
        return try {
            isoDep.connect()
            isoDep.timeout = 5_000
            authenticate(isoDep)
            readDeviceInfo(isoDep)
        } catch (e: Exception) {
            Log.w(TAG, "IsoDep probe failed", e)
            null
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
            }
        }
    }

    fun authenticate(isoDep: IsoDep) {
        selectApplication(isoDep)
        transceive(isoDep, 0x00, 0x20, 0x00, 0x01, byteArrayOf(0x20, 0x09, 0x12, 0x10))
    }

    private fun selectApplication(isoDep: IsoDep) {
        transceive(
            isoDep,
            0x00,
            0xA4,
            0x04,
            0x00,
            byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01),
        )
    }

    fun sendImage(isoDep: IsoDep, pixels: Array<IntArray>, device: IsoDepDeviceInfo, progress: (Int) -> Unit) {
        var processed = pixels
        if (device.rotated) {
            processed = rotateCw90(processed)
        }
        if (device.hflip) {
            processed = processed.map { row -> row.reversedArray() }.toTypedArray()
        }

        val packed = packPixels(processed, device.bitsPerPixel)
        var sentBlocks = 0
        var offset = 0
        for (blockNo in device.blockSizes.indices) {
            val blockSize = device.blockSizes[blockNo]
            val block = packed.copyOfRange(offset, offset + blockSize)
            offset += blockSize
            val compressed = LzoCompress.compress(block)
            val fragments = compressed.toList().chunked(MAX_FRAGMENT).map { it.toByteArray() }
            fragments.forEachIndexed { fragNo, fragment ->
                val isFinal = fragNo == fragments.lastIndex
                val p2 = if (isFinal) 0x01 else 0x00
                val data = byteArrayOf(blockNo.toByte(), fragNo.toByte()) + fragment
                transceive(isoDep, 0xF0, 0xD3, 0x00, p2, data)
            }
            sentBlocks++
            progress((sentBlocks * 85) / device.numBlocks.coerceAtLeast(1))
        }
    }

    fun refreshAndWait(isoDep: IsoDep, timeoutMs: Long = 60_000, progress: (Int) -> Unit) {
        transceive(isoDep, 0xF0, 0xD4, 0x85, 0x80, null, le = 256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = transceive(isoDep, 0xF0, 0xDE, 0x00, 0x00, null, le = 1)
            if (status.isNotEmpty() && status[0] == 0x00.toByte()) {
                progress(100)
                return
            }
            progress(90)
            Thread.sleep(500)
        }
        throw IllegalStateException("Display refresh timed out")
    }

    private fun transceive(
        isoDep: IsoDep,
        cla: Int,
        ins: Int,
        p1: Int,
        p2: Int,
        data: ByteArray?,
        le: Int = -1,
    ): ByteArray {
        val cmd = buildApdu(cla, ins, p1, p2, data, le)
        Log.v(TAG, "APDU → ${cmd.joinToString(" ") { "%02X".format(it) }}")
        val response = isoDep.transceive(cmd)
        if (response.size < 2) {
            throw IllegalStateException("APDU response too short")
        }
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        if (sw1 != 0x90 || sw2 != 0x00) {
            throw IllegalStateException("APDU failed SW=%02X%02X".format(sw1, sw2))
        }
        return if (response.size > 2) response.copyOfRange(0, response.size - 2) else byteArrayOf()
    }

    private fun buildApdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray?, le: Int): ByteArray {
        val hasData = data != null && data.isNotEmpty()
        val size = 4 + (if (hasData) 1 + data!!.size else 0) + (if (le >= 0) 1 else 0)
        val apdu = ByteArray(size)
        var i = 0
        apdu[i++] = cla.toByte()
        apdu[i++] = ins.toByte()
        apdu[i++] = p1.toByte()
        apdu[i++] = p2.toByte()
        if (hasData) {
            apdu[i++] = data!!.size.toByte()
            System.arraycopy(data, 0, apdu, i, data.size)
            i += data.size
        }
        if (le >= 0) {
            apdu[i] = if (le > 255) 0 else le.toByte()
        }
        return apdu
    }

    fun parseDeviceInfo(data: ByteArray): IsoDepDeviceInfo {
        val tlv = parseTlv(data)
        val a0 = tlv[0xA0] ?: throw IllegalStateException("Missing A0 tag in device info")
        if (a0.size < 7) throw IllegalStateException("Invalid A0 tag")
        val colorMode = a0[1].toInt() and 0xFF
        val rowsPerBlock = a0[2].toInt() and 0xFF
        val heightRaw = ((a0[3].toInt() and 0xFF) shl 8) or (a0[4].toInt() and 0xFF)
        var width = ((a0[5].toInt() and 0xFF) shl 8) or (a0[6].toInt() and 0xFF)
        val bpp = COLOR_MODE_TO_BPP[colorMode]
            ?: throw IllegalStateException("Unknown color mode 0x${colorMode.toString(16)}")
        var height = heightRaw / bpp
        val swapped = width < height
        if (swapped) {
            val tmp = width
            width = height
            height = tmp
        }
        val rotated = (width to height) in ROTATED_PANELS
        val hflip = swapped && !rotated
        val serial = tlv[0xC0]?.toString(Charsets.US_ASCII) ?: ""
        return IsoDepDeviceInfo(
            width = width,
            height = height,
            bitsPerPixel = bpp,
            rowsPerBlock = rowsPerBlock,
            serialNumber = serial,
            rotated = rotated,
            hflip = hflip,
            raw = data,
        )
    }

    private fun parseTlv(data: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = 0
        while (offset + 2 <= data.size) {
            val tag = data[offset].toInt() and 0xFF
            val len = data[offset + 1].toInt() and 0xFF
            offset += 2
            if (offset + len > data.size) break
            result[tag] = data.copyOfRange(offset, offset + len)
            offset += len
        }
        return result
    }

    private fun packPixels(pixels: Array<IntArray>, bpp: Int): ByteArray {
        val ppb = 8 / bpp
        val out = ArrayList<Byte>(pixels.size * (pixels[0].size / ppb))
        for (row in pixels) {
            val bytesPerRow = row.size / ppb
            for (byteIdx in 0 until bytesPerRow) {
                val pixelOffset = (bytesPerRow - 1 - byteIdx) * ppb
                var value = 0
                for (i in 0 until ppb) {
                    value = value or (row[pixelOffset + i] shl (i * bpp))
                }
                out.add(value.toByte())
            }
        }
        return out.toByteArray()
    }

    private fun rotateCw90(pixels: Array<IntArray>): Array<IntArray> {
        val h = pixels.size
        val w = pixels[0].size
        return Array(w) { x ->
            IntArray(h) { y ->
                pixels[h - 1 - y][x]
            }
        }
    }
}
