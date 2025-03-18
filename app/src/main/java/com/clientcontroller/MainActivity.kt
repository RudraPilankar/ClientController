package com.clientcontroller

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var data: Array<String>
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var preferences: SharedPreferences
    private val OVERLAY_PERMISSION_REQUEST_CODE: Int = 1
    private val EXTERNAL_MANAGER_REQUEST_CODE: Int = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        data = preferences.getStringSet("Devices", setOf())!!.toTypedArray()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, data)
        binding.listView.adapter = adapter
        binding.listView.setOnItemClickListener { parent, view, position, id ->
            val deviceId = data[position]
            val i = Intent(this, ControlDeviceActivity::class.java)
            i.putExtra("Position", position)
            i.putExtra("DeviceID", deviceId)
            startActivity(i)
        }
        binding.floatingActionButton.setOnClickListener {
            val i = Intent(this, AddDeviceActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(i)
            finish()
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    val uri = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            uri
                        ), EXTERNAL_MANAGER_REQUEST_CODE
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.MANAGE_EXTERNAL_STORAGE), 0)
        else
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES), 0)
        Log.d("MainActivity", "Is NEARBY_WIFI_DEVICES granted: ${ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)}")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        val uri = Uri.parse("package:com.clientcontroller")
                        startActivityForResult(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                uri
                            ), EXTERNAL_MANAGER_REQUEST_CODE
                        )
                    }
                }
            }
        } else if (requestCode == EXTERNAL_MANAGER_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    val uri = Uri.parse("package:com.clientcontroller")
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            uri
                        ), EXTERNAL_MANAGER_REQUEST_CODE
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        data = preferences.getStringSet("Devices", setOf())!!.toTypedArray()
        adapter.notifyDataSetChanged()
    }
}