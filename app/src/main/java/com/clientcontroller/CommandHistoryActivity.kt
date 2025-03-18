package com.clientcontroller

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivityCommandHistoryBinding
import java.io.File

class CommandHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCommandHistoryBinding
    private lateinit var currentDeviceID: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandHistoryBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        currentDeviceID = intent.getStringExtra("DeviceID").toString()
        val historyFile = File(File(Environment.getExternalStorageDirectory(), "ClientController"), "$currentDeviceID.txt")
        if (!historyFile.exists()) {
            Toast.makeText(this, "No history available", Toast.LENGTH_LONG).show()
        } else {
            val text = historyFile.readText()
            binding.historyView.text = text
        }
    }
}