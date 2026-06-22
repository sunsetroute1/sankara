package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView

object PanelPreview {
    fun bind(imageView: ImageView, bitmap: Bitmap, panelWidth: Int, panelHeight: Int) {
        imageView.adjustViewBounds = true
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setImageBitmap(bitmap)
        imageView.post {
            val container = imageView.parent as? View ?: return@post
            val available = container.width - container.paddingLeft - container.paddingRight
            if (available <= 0 || panelWidth <= 0 || panelHeight <= 0) return@post
            val targetHeight = (available * panelHeight.toFloat() / panelWidth).toInt().coerceAtLeast(1)
            val params = imageView.layoutParams
            if (params.height != targetHeight) {
                params.height = targetHeight
                imageView.layoutParams = params
            }
        }
    }
}
