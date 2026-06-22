package com.joshuatz.nfceinkwriter

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

object NotificationArtStore {

    private val byPackage = ConcurrentHashMap<String, Bitmap>()

    fun captureFromNotification(context: Context, sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val notification = sbn.notification ?: return

        notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)?.let {
            byPackage[pkg] = BitmapUtils.toSoftwareBitmap(it) ?: it
            return
        }

        val largeIcon = notification.getLargeIcon()
        if (largeIcon != null) {
            iconToBitmap(context, largeIcon)?.let { bmp ->
                byPackage[pkg] = bmp
            }
        }
    }

    fun get(packageName: String): Bitmap? = byPackage[packageName]

    private fun iconToBitmap(context: Context, icon: Icon): Bitmap? {
        val drawable = icon.loadDrawable(context) ?: return null
        if (drawable is BitmapDrawable) {
            return BitmapUtils.toSoftwareBitmap(drawable.bitmap)
        }
        val w = drawable.intrinsicWidth.coerceIn(1, 512)
        val h = drawable.intrinsicHeight.coerceIn(1, 512)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }
}
