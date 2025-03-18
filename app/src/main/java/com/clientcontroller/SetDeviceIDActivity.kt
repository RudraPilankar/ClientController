package com.clientcontroller

import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivitySetDeviceIdBinding
import com.google.firebase.firestore.FirebaseFirestore

class SetDeviceIDActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetDeviceIdBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySetDeviceIdBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val pos = intent.extras!!.getInt("Position")
        val deviceId = intent.extras!!.getString("DeviceID")
        binding.setBtn.setOnClickListener {
            val newDeviceId = binding.newDeviceIdBox.text.toString()
            if (newDeviceId.replace(" ", "").isEmpty()) {
                binding.newDeviceIdBox.error = "Please enter a new device ID"
                return@setOnClickListener
            }
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            val data = preferences.getStringSet("Devices", setOf())!!.toMutableList()
            for (id in data) {
                if (id == newDeviceId) {
                    binding.newDeviceIdBox.error = "Please enter a new device ID"
                    return@setOnClickListener
                }
            }
            FirebaseFirestore.getInstance().collection("Messages").document().set(hashMapOf("For" to deviceId, "Message" to "SET_DEVICE_ID $newDeviceId")).addOnSuccessListener {
                data.removeAt(pos)
                val idSet = preferences.getStringSet("Devices", null)!!.toMutableSet()
                idSet.clear()
                idSet.addAll(data)
                idSet.add(newDeviceId)
                with (preferences.edit()) {
                    putStringSet("Devices", idSet)
                    commit()
                }
                Toast.makeText(this, "The new ID will work after the remote device receives the command", Toast.LENGTH_LONG).show()
                val i = Intent(this, MainActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(i)
                finishAffinity()
            }.addOnFailureListener {
                Toast.makeText(this, "Error: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}