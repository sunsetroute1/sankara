package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap

object CardStudioRenderer {
    fun render(snapshot: CardStudioSnapshot, width: Int, height: Int): Bitmap {
        return when (snapshot.cardType) {
            CardStudioSnapshot.TYPE_QR -> {
                val content = QrCodeGenerator.normalizeUrl(
                    snapshot.fields[CardStudioSnapshot.KEY_QR_CONTENT].orEmpty(),
                )
                val label = snapshot.fields[CardStudioSnapshot.KEY_QR_LABEL].orEmpty()
                CardRenderer.renderQrCard(width, height, content, label)
            }
            CardStudioSnapshot.TYPE_WIFI -> {
                val ssid = snapshot.fields[CardStudioSnapshot.KEY_WIFI_SSID].orEmpty()
                val password = snapshot.fields[CardStudioSnapshot.KEY_WIFI_PASSWORD].orEmpty()
                val security = snapshot.wifiSecurity
                CardRenderer.renderWifiCard(width, height, ssid, password, security)
            }
            CardStudioSnapshot.TYPE_CONTACT -> CardRenderer.renderContactCard(
                width,
                height,
                snapshot.fields[CardStudioSnapshot.KEY_CONTACT_NAME].orEmpty(),
                snapshot.fields[CardStudioSnapshot.KEY_CONTACT_TITLE].orEmpty(),
                snapshot.fields[CardStudioSnapshot.KEY_CONTACT_PHONE].orEmpty(),
                snapshot.fields[CardStudioSnapshot.KEY_CONTACT_EMAIL].orEmpty(),
                snapshot.fields[CardStudioSnapshot.KEY_CONTACT_WEBSITE].orEmpty(),
            )
            CardStudioSnapshot.TYPE_LINKS -> CardRenderer.renderLinksCard(
                width,
                height,
                snapshot.fields[CardStudioSnapshot.KEY_LINKS_HANDLE].orEmpty(),
                listOf(
                    snapshot.fields[CardStudioSnapshot.KEY_LINK_1].orEmpty(),
                    snapshot.fields[CardStudioSnapshot.KEY_LINK_2].orEmpty(),
                    snapshot.fields[CardStudioSnapshot.KEY_LINK_3].orEmpty(),
                    snapshot.fields[CardStudioSnapshot.KEY_LINK_4].orEmpty(),
                ),
            )
            else -> CardRenderer.renderQrCard(width, height, "", "")
        }
    }
}
