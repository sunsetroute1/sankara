package com.joshuatz.nfceinkwriter.nfc.waveshare



import android.graphics.Bitmap

import android.graphics.Matrix

import android.util.Log



/**

 * Pack a bitmap for the Waveshare 2.7" Rev2.2 module (SDK type 6, [activity.a.r]).

 *

 * Official [activity.a.r] type-6 path (k[6]==0):

 * - 270° rotation on the panel-sized bitmap before sampling

 * - getPixels from the rotated bitmap (stride = rotated.width)

 * - Pack g[6]=176 rows × (f[6]/8)=33 bytes using f[6]=264 as the row stride index

 * - MSB-first: set bit when (pixel & 0xFF) > 128, then XOR-invert each byte

 */

object Rev22BitmapEncoder {



    private const val TAG = "Rev22BitmapEncoder"

    /** Panel logical width — activity.a.f[6]. */

    private const val PACK_STRIDE = 264

    /** Panel height — activity.a.g[6]. */

    private const val PACK_HEIGHT = 176



    fun encode(bitmap: Bitmap): Rev22EncodedImage {

        require(bitmap.width == PACK_STRIDE && bitmap.height == PACK_HEIGHT) {

            "Bitmap must be ${PACK_STRIDE}×$PACK_HEIGHT, got ${bitmap.width}×${bitmap.height}"

        }



        val rotated = rotate270(bitmap)

        val rotatedW = rotated.width

        val rotatedH = rotated.height

        val rowBytes = (PACK_STRIDE + 7) / 8

        val planeSize = rowBytes * PACK_HEIGHT

        val pixels = IntArray(rotatedW * rotatedH)

        rotated.getPixels(pixels, 0, rotatedW, 0, 0, rotatedW, rotatedH)

        if (rotated !== bitmap) {

            rotated.recycle()

        }



        Log.i(

            TAG,

            "Rotated ${bitmap.width}×${bitmap.height} → ${rotatedW}×${rotatedH} " +

                "stride=$PACK_STRIDE plane=$planeSize",

        )



        val blackWhite = ByteArray(planeSize)

        val chroma = ByteArray(planeSize)



        for (row in 0 until PACK_HEIGHT) {

            for (xByte in 0 until rowBytes) {

                var value = 0

                for (bit in 0 until 8) {

                    value = value shl 1

                    val x = xByte * 8 + bit

                    if (x < PACK_STRIDE) {

                        // Official indexes with f[6]=264 even though getPixels stride is rotated.width.

                        val index = x + row * PACK_STRIDE

                        if (index in pixels.indices) {

                            val px = pixels[index]

                            if ((px and 0xFF) > 128) {

                                value = value or 1

                            }

                        }

                    }

                }

                blackWhite[row * rowBytes + xByte] = (value xor 0xFF).toByte()

            }

        }



        Log.i(

            TAG,

            "Encoded $planeSize bytes bwHead=${blackWhite.take(8).joinToString(" ") { "%02X".format(it) }}",

        )

        return Rev22EncodedImage(blackWhite, chroma)

    }



    private fun rotate270(source: Bitmap): Bitmap {

        val matrix = Matrix().apply { setRotate(270f) }

        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)

    }

}


