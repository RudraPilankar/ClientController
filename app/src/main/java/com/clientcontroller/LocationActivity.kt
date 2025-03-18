package com.clientcontroller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.transition.Visibility
import com.clientcontroller.databinding.ActivityLocationBinding
import java.math.BigDecimal

class LocationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocationBinding
    private lateinit var latitude: String
    private lateinit var longitude: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val locationInfo = intent.extras!!.getString("LocationInfo")
        for (item in locationInfo!!.split(", ")) {
            if (item.startsWith("Latitude: ")) {
                latitude = item.removePrefix("Latitude: ")
                binding.latitudeView.text = item
            } else if (item.startsWith("Longitude: ")) {
                longitude = item.removePrefix("Longitude: ")
                binding.longitudeView.text = item
            } else if (item.startsWith("Accuracy: ")) {
                val accuracy = item.replace("Accuracy: ", "Location Accuracy: ") + " m"
                binding.locationAccuracyView.text = accuracy
                binding.locationAccuracyView.visibility = View.VISIBLE
            } else if (item.startsWith("Speed: ")) {
                binding.speedView.text = "$item m/s"
                binding.speedView.visibility = View.VISIBLE
                binding.speedViewKmph.text =
                    "Speed: ${item.removePrefix("Speed: ").toBigDecimal() * (18 / 5).toBigDecimal()} km/hr"
                binding.speedViewKmph.visibility = View.VISIBLE
            } else if (item.startsWith("Speed Accuracy: ")) {
                binding.speedAccuracyView.text = "$item m/s"
                binding.speedAccuracyView.visibility = View.VISIBLE
                binding.speedAccuracyViewKmph.text =
                    "Speed Accuracy: ${item.removePrefix("Speed Accuracy: ").toBigDecimal() * (18 / 5).toBigDecimal()} km/hr"
                binding.speedAccuracyViewKmph.visibility = View.VISIBLE
            } else if (item.startsWith("Altitude: ")) {
                val altitude = "$item m"
                binding.altitudeView.text = altitude
                binding.altitudeView.visibility = View.VISIBLE
            } else if (item.startsWith("Vertical Accuracy: ")) {
                val accuracy = item.replace("Vertical Accuracy: ", "Altitude Accuracy: ") + " m"
                binding.altitudeAccuracyView.text = accuracy
                binding.altitudeAccuracyView.visibility = View.VISIBLE
            } else if (item.startsWith("MSL Altitude: ")) {
                val altitude = "$item m"
                binding.mslAltitudeView.text = altitude
                binding.mslAltitudeView.visibility = View.VISIBLE
            } else if (item.startsWith("MSL Altitude Accuracy: ")) {
                val accuracy = "$item m"
                binding.mslAltitudeAccuracyView.text = accuracy
                binding.mslAltitudeAccuracyView.visibility = View.VISIBLE
            } else if (item.startsWith("Bearing: ")) {
                val bearing = item
                binding.bearingView.text = bearing
                binding.bearingView.visibility = View.VISIBLE
            } else if (item.startsWith("Bearing Accuracy: ")) {
                val accuracy = item
                binding.bearingAccuracyView.text = accuracy
                binding.bearingAccuracyView.visibility = View.VISIBLE
            }
        }

        binding.openInGoogleMapsBtn.setOnClickListener {
            val mapUri = Uri.parse("geo:0,0?q=$latitude,$longitude(${intent.extras!!.getString("DeviceID").toString()})")
            Intent(Intent.ACTION_VIEW, mapUri).also {
                it.setPackage("com.google.android.apps.maps")
                startActivity(it)
            }
        }
    }
}