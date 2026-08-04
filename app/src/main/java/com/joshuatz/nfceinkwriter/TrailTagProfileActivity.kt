package com.joshuatz.nfceinkwriter

import android.os.Bundle
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.materialswitch.MaterialSwitch
import com.joshuatz.nfceinkwriter.trailtag.AdventurerComms
import com.joshuatz.nfceinkwriter.trailtag.EmergencyContact
import com.joshuatz.nfceinkwriter.trailtag.MedicalInfo
import com.joshuatz.nfceinkwriter.trailtag.TrackingLink
import com.joshuatz.nfceinkwriter.trailtag.TrackingProviderRegistry
import com.joshuatz.nfceinkwriter.trailtag.TrailTagProfile
import com.joshuatz.nfceinkwriter.trailtag.TrailTagRepository
import com.joshuatz.nfceinkwriter.trailtag.VehicleInfo

/** Edit local TrailTag safety profile — stored on device; hosted publish is optional. */
class TrailTagProfileActivity : ThemedActivity() {

    private lateinit var repository: TrailTagRepository

    private val trackingFields = mapOf(
        TrackingProviderRegistry.garminInReach.id to R.id.inputGarminInreach,
        TrackingProviderRegistry.garminLiveTrack.id to R.id.inputGarminLivetrack,
        TrackingProviderRegistry.stravaBeacon.id to R.id.inputStravaBeacon,
        TrackingProviderRegistry.googleMaps.id to R.id.inputGoogleMaps,
        TrackingProviderRegistry.customUrl.id to R.id.inputOtherTracking,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trail_tag_profile)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.trailTagProfileAppBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.trailTagProfileScroll))

        repository = TrailTagRepository(this)
        findViewById<MaterialToolbar>(R.id.trail_tag_profile_toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnSaveProfile).setOnClickListener { saveProfile() }

        bindProfile(repository.getProfile())
    }

    private fun bindProfile(profile: TrailTagProfile) {
        findViewById<TextInputEditText>(R.id.inputProfileName)?.setText(profile.name)
        val comms = profile.comms
        findViewById<TextInputEditText>(R.id.inputAdventurerPhone)?.setText(comms.mobilePhone)
        findViewById<MaterialSwitch>(R.id.switchSatelliteCapable)?.isChecked = comms.satelliteCapable
        findViewById<TextInputEditText>(R.id.inputInReachSms)?.setText(comms.inReachSmsNumber)
        val contact = profile.contacts.firstOrNull() ?: EmergencyContact()
        findViewById<TextInputEditText>(R.id.inputContactName)?.setText(contact.name)
        findViewById<TextInputEditText>(R.id.inputContactPrimary)?.setText(contact.primaryPhone)
        findViewById<TextInputEditText>(R.id.inputContactSecondary)?.setText(contact.secondaryPhone)
        findViewById<TextInputEditText>(R.id.inputBloodType)?.setText(profile.medical.bloodType)
        findViewById<TextInputEditText>(R.id.inputAllergies)?.setText(profile.medical.allergies)
        findViewById<TextInputEditText>(R.id.inputMedicalNotes)?.setText(profile.medical.notes)

        trackingFields.forEach { (providerId, viewId) ->
            val url = profile.trackingLinks.firstOrNull { it.providerId == providerId }?.url.orEmpty()
            findViewById<TextInputEditText>(viewId)?.setText(url)
        }

        findViewById<TextInputEditText>(R.id.inputVehicleMake)?.setText(profile.vehicle.makeModel)
        findViewById<TextInputEditText>(R.id.inputVehicleColor)?.setText(profile.vehicle.color)
        findViewById<TextInputEditText>(R.id.inputVehiclePlate)?.setText(profile.vehicle.licensePlate)
    }

    private fun saveProfile() {
        val existing = repository.getProfile()
        val contact = EmergencyContact(
            name = findViewById<TextInputEditText>(R.id.inputContactName)?.text?.toString().orEmpty().trim(),
            primaryPhone = findViewById<TextInputEditText>(R.id.inputContactPrimary)?.text?.toString().orEmpty().trim(),
            secondaryPhone = findViewById<TextInputEditText>(R.id.inputContactSecondary)?.text?.toString().orEmpty().trim(),
        )
        val trackingLinks = TrackingProviderRegistry.defaultSlots.map { slot ->
            val viewId = trackingFields[slot.id]
            val url = viewId?.let { findViewById<TextInputEditText>(it)?.text?.toString().orEmpty().trim() }.orEmpty()
            TrackingLink(slot.id, slot.name, url)
        }
        val profile = existing.copy(
            name = findViewById<TextInputEditText>(R.id.inputProfileName)?.text?.toString().orEmpty().trim(),
            comms = AdventurerComms(
                mobilePhone = findViewById<TextInputEditText>(R.id.inputAdventurerPhone)?.text?.toString().orEmpty().trim(),
                satelliteCapable = findViewById<MaterialSwitch>(R.id.switchSatelliteCapable)?.isChecked == true,
                inReachSmsNumber = findViewById<TextInputEditText>(R.id.inputInReachSms)?.text?.toString().orEmpty().trim(),
                checkInSmsBody = existing.comms.checkInSmsBody,
                inReachSmsBody = existing.comms.inReachSmsBody,
            ),
            contacts = listOf(contact),
            medical = MedicalInfo(
                bloodType = findViewById<TextInputEditText>(R.id.inputBloodType)?.text?.toString().orEmpty().trim(),
                allergies = findViewById<TextInputEditText>(R.id.inputAllergies)?.text?.toString().orEmpty().trim(),
                notes = findViewById<TextInputEditText>(R.id.inputMedicalNotes)?.text?.toString().orEmpty().trim(),
            ),
            trackingLinks = trackingLinks,
            vehicle = VehicleInfo(
                makeModel = findViewById<TextInputEditText>(R.id.inputVehicleMake)?.text?.toString().orEmpty().trim(),
                color = findViewById<TextInputEditText>(R.id.inputVehicleColor)?.text?.toString().orEmpty().trim(),
                licensePlate = findViewById<TextInputEditText>(R.id.inputVehiclePlate)?.text?.toString().orEmpty().trim(),
            ),
        )
        repository.saveProfile(profile)
        Toast.makeText(this, R.string.trail_tag_profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
