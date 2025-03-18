package com.clientcontroller

import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivityAddDeviceBinding

class AddDeviceActivity : AppCompatActivity() {
    lateinit var binding: ActivityAddDeviceBinding

    @Deprecated("Deprecated in Java",
        ReplaceWith("super.onBackPressed()", "androidx.appcompat.app.AppCompatActivity")
    )
    override fun onBackPressed() {
        super.onBackPressed()
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(i)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAddDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binding.addBtn.setOnClickListener {
            val deviceId = binding.deviceIdBox.text.toString()
            if (deviceId.replace(" ", "").isEmpty()) {
                binding.deviceIdBox.error = "Please enter the device ID"
                return@setOnClickListener
            }
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            val data = preferences.getStringSet("Devices", setOf())!!.toMutableSet()
            if (!data.contains(deviceId)) {
                data.add(deviceId)
                with(preferences.edit()) {
                    putStringSet("Devices", data)
                    commit()
                }
                val i = Intent(this, MainActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(i)
                finish()
            } else {
                binding.deviceIdBox.error = "The device is already added"
                return@setOnClickListener
            }
        }
    }
}