package com.clientcontroller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivityBledistanceTrackingBinding
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import java.util.UUID

class BLEDistanceTrackingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBledistanceTrackingBinding
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val REQUEST_ENABLE_BT = 1  // Request code for enabling Bluetooth
    private val REQUEST_BT_PERMISSIONS = 101  // Request code for permissions
    private lateinit var currentDeviceId: String
    private var deviceName: String? = null
    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var snapshotListener: EventListener<QuerySnapshot>
    private lateinit var listenerReg: ListenerRegistration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBledistanceTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        currentDeviceId = intent.getStringExtra("DeviceID").toString()
        binding.deviceIdView.text = currentDeviceId

        if (checkBluetoothPermissions()){
            // Initialize Bluetooth adapter
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter

            // Check if Bluetooth is supported and enabled
            if (!bluetoothAdapter.isEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    enableBluetooth()
                } else {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        bluetoothAdapter.enable()
                        // Start BLE scan for all devices using the new API
                        startBleScan()
                        return
                    } else {
                        Toast.makeText(this, "Please grant necessary permissions", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                // Start BLE scan for all devices using the new API
                startBleScan()
            }
        }

        sendCommandByFirestore("GET_BLUETOOTH_ADAPTER_NAME")
    }

    private fun checkBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
        }
        if (permissions.isNotEmpty()){
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_BT_PERMISSIONS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_PERMISSIONS){
            if(grantResults.all { it == PackageManager.PERMISSION_GRANTED }){
                startBleScan()
            } else{
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        snapshotListener = EventListener<QuerySnapshot> { snapshot, error ->
            if (error != null) {
                // Handle error
                return@EventListener
            }
            if (snapshot != null) {
                for (change in snapshot.documentChanges) {
                    if (change.type == DocumentChange.Type.ADDED &&
                        (change.document.getString("For") == serverID || change.document.getString("For") == legacyDefaultServerID) &&
                        change.document.getString("From") == currentDeviceID) {
                        val message = change.document.getString("Message")
                        var delete = true
                        Log.d("BLEDistanceTrackingActivity", "Got message: $message")
                        if (message?.startsWith("GET_BLUETOOTH_ADAPTER_NAME") == true) {
                            deviceName = message.removePrefix("GET_BLUETOOTH_ADAPTER_NAME: ")
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        } else {
                            delete = false
                        }
                        if (delete) {
                            firestore.collection("Messages").document(change.document.id).delete().addOnFailureListener {
                                Toast.makeText(this, "Error: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
        listenerReg = firestore.collection("Messages").addSnapshotListener(snapshotListener)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                if(checkBluetoothPermissions()){
                    // Start BLE scan for all devices using the new API
                    startBleScan()
                }
            } else {
                Toast.makeText(
                    this,
                    "Please enable Bluetooth and grant necessary permissions",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun enableBluetooth() {
        // Intent to turn on Bluetooth
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        } else {
            Toast.makeText(this, "Please enable Bluetooth and grant necessary permissions", Toast.LENGTH_LONG).show()
        }
    }

    // --- New BLE scanning code using the new API ---
    private fun startBleScan() {
        Log.d("BLEDistanceTrackingActivity", "Starting BLE scan (using startScan)")
        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // Start scanning with no filters (null)
            bluetoothLeScanner?.startScan(null, scanSettings, newScanCallback)
        } else {
            Log.d("BLEDistanceTrackingActivity", "Bluetooth scan permission denied")
            Toast.makeText(this, "Please enable Bluetooth and grant necessary permissions", Toast.LENGTH_LONG).show()
        }
    }

    // New ScanCallback implementation for the new API
    private val newScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (ActivityCompat.checkSelfPermission(this@BLEDistanceTrackingActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (result.device.name != null && result.device.name == deviceName) {
                    val txPower = result.scanRecord?.txPowerLevel
                    val rssi = result.rssi
                    // Only calculate if TX power is available
                    if (txPower != null) {
                        val distance = calculateDistance(rssi, -20)
                        Log.e(
                            "BLEDistanceTrackingActivity",
                            "Device found: ${result.device.address}, RSSI: $rssi dBm, TxPowerLevel: $txPower, Estimated distance: ${"%.2f".format(distance)} meters"
                        )
                    } else {
                        Log.e("BLEDistanceTrackingActivity", "TxPowerLevel not available")
                    }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLEDistanceTrackingActivity", "BLE scan failed with error code: $errorCode")
        }
    }

    /**
     * Calculates the estimated distance between the devices.
     *
     * @param rssi The received signal strength in dBm.
     * @param txPower The advertised TX power level (expected RSSI at 1 meter) in dBm.
     * @param pathLossExponent The path loss exponent (2.0 is free-space; 2.7–3.5 in typical indoor environments).
     * @return The estimated distance in meters.
     */
    fun calculateDistance(rssi: Int, txPower: Int, pathLossExponent: Double = 2.0): Double {
        // If RSSI is zero, we cannot determine distance reliably.
        if (rssi == 0) return -1.0
        return Math.pow(10.0, ((txPower - rssi).toDouble()) / (10 * pathLossExponent))
    }

    private fun sendCommandByFirestore(command: String): String? {
        // Generate a new document reference with an auto-generated ID
        val newDocRef = firestore.collection("Messages").document()
        val documentId = newDocRef.id
        // Prepare the data to be set in the document
        val data = hashMapOf(
            "For" to currentDeviceID,
            "From" to serverID,
            "Message" to command
        )
        // Attempt to set the data in the document
        newDocRef.set(data)
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Error: ${exception.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        Log.d("BLEDistanceTrackingActivity", "Sent command: $command")
        return documentId
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(newScanCallback)
        }
    }
}
