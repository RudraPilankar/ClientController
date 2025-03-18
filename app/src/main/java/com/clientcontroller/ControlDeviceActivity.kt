package com.clientcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.provider.OpenableColumns
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import com.clientcontroller.databinding.ActivityControlDeviceBinding
import com.clientcontroller.databinding.LayoutDialogBinding
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import java.io.File

var clientPhoneNumber = "+917028972643"
var hasClientGotPhoneNumber = false
var hasClientGotServerPhoneNumber = false
var currentDeviceID: String = ""
const val legacyDefaultServerID = "89dey9p8yxstpgup7gp98gxgy"
var serverID = "Fx7]`£C?K<H`}*X}<9xwMgEn5plKtLYW"

var wifiP2pManager: WifiP2pManager? = null
var wifiP2pChannel: WifiP2pManager.Channel? = null
var wifiP2pConnectedDeviceID = ""
var isWifiP2pConnected = false

val serviceName = "ClientWiFiP2PServer"
val serviceType = "_presence._tcp"

class SMSReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null) {
            if (intent.action.equals("android.provider.Telephony.SMS_RECEIVED")) {
                val bundle = intent.extras
                if (bundle != null) {
                    val pdus = bundle["pdus"] as Array<*>
                    val messages = arrayOfNulls<SmsMessage>(pdus.size)
                    for (i in pdus.indices) {
                        messages[i] = SmsMessage.createFromPdu(pdus[i] as ByteArray)
                    }
                    if (messages.isNotEmpty()) {
                        for (message in messages) {
                            if (message?.originatingAddress == clientPhoneNumber) {
                                val messageBody = message.messageBody
                                if (messageBody.startsWith("GET_CURRENT_LOCATION: ") || messageBody.startsWith("GET_LAST_KNOWN_LOCATION: ")) {
                                    if (messageBody.startsWith("GET_CURRENT_LOCATION: Operation failed") || messageBody.startsWith("GET_LAST_KNOWN_LOCATION: Operation failed")) {
                                        Toast.makeText(context, messageBody, Toast.LENGTH_SHORT).show()
                                    } else {
                                        val i = Intent(context, LocationActivity::class.java)
                                        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        i.putExtra("LocationInfo",
                                            messageBody.removePrefix("GET_CURRENT_LOCATION: ")
                                                .removePrefix("GET_LAST_KNOWN_LOCATION: ")
                                        )
                                        i.putExtra("DeviceID", "")
                                        context?.startActivity(i)
                                    }
                                } else if (messageBody == "SET_PHONE_NO_TO_USE: Operation completed successfully") {
                                    hasClientGotPhoneNumber = true
                                    Toast.makeText(context, messageBody, Toast.LENGTH_LONG).show()
                                } else if (messageBody == "PING") {
                                    Toast.makeText(context, "Got ping", Toast.LENGTH_LONG).show()
                                } else if (messageBody.startsWith("START_WIFI_P2P_SERVER")) {
                                    if (context != null)
                                        parseWifiP2pMessage(context, messageBody)
                                } else {
                                    Toast.makeText(context, messageBody, Toast.LENGTH_LONG).show()
                                }
                                val err = "Error: Failed to save to history file"
                                try {
                                    if (currentDeviceID != "") {
                                        val dir = File(
                                            Environment.getExternalStorageDirectory(),
                                            "ClientController"
                                        )
                                        if (!dir.exists()) {
                                            if (!dir.mkdirs()) {
                                                Toast.makeText(context, err, Toast.LENGTH_LONG)
                                                    .show()
                                            }
                                        }
                                        var file = File(dir, "$currentDeviceID.txt")
                                        if (file.length() > 100000) {
                                            var name = "$currentDeviceID.1.txt"
                                            var i = 2
                                            while (File(dir, name).exists()) {
                                                name = "$currentDeviceID.$i.txt"
                                                i += 1
                                            }
                                            file.renameTo(
                                                File(
                                                    dir,
                                                    name
                                                )
                                            )
                                            file = File(dir, "$currentDeviceID.txt")
                                        }
                                        if (!file.exists()) {
                                            file.createNewFile()
                                        }
                                        file.appendText("Received:\n$messageBody\n\n")
                                    }
                                } catch (ex: Exception) {
                                    Toast.makeText(context, ex.localizedMessage, Toast.LENGTH_LONG).show()
                                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun parseWifiP2pMessage(context: Context, messageBody: String) {
    val deviceId = currentDeviceID
    Toast.makeText(context, messageBody, Toast.LENGTH_LONG).show()
    if (messageBody.startsWith("START_WIFI_P2P_SERVER: Operation failed - ")) {
        return
    } else {
        Log.d("ControlDeviceActivity", "Trying to connect...")
        val wifiP2pDnsSdServiceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        wifiP2pManager!!.setDnsSdResponseListeners(
            wifiP2pChannel,
            object : WifiP2pManager.DnsSdServiceResponseListener {
                override fun onDnsSdServiceAvailable(
                    instanceName: String?,
                    registrationType: String?,
                    srcDevice: WifiP2pDevice?
                ) {
                    Log.i("ControlDeviceActivity", "Found service $instanceName")
                    if (instanceName.equals(serviceName)) {
                        val wifiP2pConfig = WifiP2pConfig()
                        wifiP2pConfig.deviceAddress = srcDevice?.deviceAddress
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED && (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.NEARBY_WIFI_DEVICES
                            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                        ) {
                            wifiP2pManager!!.connect(
                                wifiP2pChannel,
                                wifiP2pConfig, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        Log.i("ControlDeviceActivity", "Connected to $deviceId")
                                        Toast.makeText(context, "Connected to $deviceId", Toast.LENGTH_LONG).show()
                                        wifiP2pConnectedDeviceID = deviceId
                                        isWifiP2pConnected = true
                                    }

                                    override fun onFailure(
                                        reason: Int
                                    ) {
                                        Toast.makeText(
                                            context,
                                            "Error: Failed to connect to $deviceId - reason $reason",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        Log.e("ControlDeviceActivity", "Failed to connect to $deviceId - reason $reason")
                                    }
                                }
                            )
                        }
                    }
                }
            },
            object : WifiP2pManager.DnsSdTxtRecordListener {
                override fun onDnsSdTxtRecordAvailable(
                    fullDomainName: String?,
                    txtRecordMap: MutableMap<String, String>?,
                    srcDevice: WifiP2pDevice?
                ) {
                }
            }
        )
        wifiP2pManager!!.addServiceRequest(
            wifiP2pChannel,
            wifiP2pDnsSdServiceRequest,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(
                        "ControlDeviceActivity",
                        "Added service request"
                    )
                    val hasLocation = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val hasNearby = ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    if (hasNearby && hasLocation) {
                        wifiP2pManager!!.discoverServices(
                            wifiP2pChannel,
                            object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    Log.i("ControlDeviceActivity", "Discovered services")
                                }

                                override fun onFailure(
                                    reason: Int
                                ) {
                                    Log.e("ControlDeviceActivity", "Failed to discover services - reason $reason")
                                }
                            }
                        )
                    } else {
                        Log.e("ControlDeviceActivity", "Permission not granted to discover services")
                    }
                    Log.i("PermissionCheck", "Location granted: $hasLocation, Nearby granted: $hasNearby")
                }

                override fun onFailure(
                    reason: Int
                ) {
                    Log.e("ControlDeviceActivity", "Failed to add service request - reason $reason")
                }
            }
        )
    }
}

class ControlDeviceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityControlDeviceBinding
    private lateinit var data: Array<String>
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var preferences: SharedPreferences
    private lateinit var firestore: FirebaseFirestore
    private lateinit var snapshotListener: EventListener<QuerySnapshot>
    private lateinit var listenerReg: ListenerRegistration

    private val REQUEST_CODE_PICK_FILE = 1

    private var serverPhoneNumber = ""

    private lateinit var storage: FirebaseStorage
    var isUsingSMS = false

    val wifiDirectBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    // Get connection state info.
                    val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo != null && !networkInfo.isConnected && wifiP2pConnectedDeviceID.isNotEmpty()) {
                        // Show toast when disconnected.
                        Log.d("ControlDeviceActivity", "Device $wifiP2pConnectedDeviceID disconnected")
                        Toast.makeText(context, "Device $wifiP2pConnectedDeviceID disconnected", Toast.LENGTH_LONG).show()
                        wifiP2pConnectedDeviceID = ""
                        isWifiP2pConnected = false
                    }
                }
            }
        }
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
                // Handle any errors that occur during the write operation
                Toast.makeText(this, "Error: ${exception.localizedMessage}", Toast.LENGTH_LONG).show()
            }

        // Return the ID of the created document
        return documentId
    }

    private fun sendCommandBySMS(command: String) {
        if (hasClientGotPhoneNumber && hasClientGotServerPhoneNumber) {
            try {
                val smsManager: SmsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(
                    clientPhoneNumber,
                    serverPhoneNumber,
                    command,
                    null,
                    null
                )
                Log.d("SendCommandBySMS", "Sending command by SMS to $clientPhoneNumber from $serverPhoneNumber: $command")
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        } else {
            Toast.makeText(this, "Error: Client has not got which phone number to use", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityControlDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(wifiDirectBroadcastReceiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), RECEIVER_EXPORTED)
        else
            registerReceiver(wifiDirectBroadcastReceiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()
        currentDeviceID = intent.extras!!.getString("DeviceID").toString()
        val pos  = intent.extras!!.getInt("Position")
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        binding.textView.text = currentDeviceID
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager!!.initialize(
            this,
            mainLooper, null
        )

        data = arrayOf(
            "Ping",
            "Get Current Location",
            "Get Last Known Location",
            "Start Ringing",
            "Stop Ringing",
            "Increase Volume",
            "Decrease Volume",
            "Mute Volume",
            "Unmute Volume",
            "Start BLE Advertising",
            "Stop BLE Advertising",
            "Get Power Info",
            "Get Battery Status",
            "Get Wi-Fi Status",
            "Get Mobile Data Status",
            "Get Local Network Info",
            "Turn On Wi-Fi",
            "Ask to Turn On Wi-Fi",
            "Turn Off Wi-Fi",
            "Turn On Bluetooth",
            "Ask to Turn On Bluetooth",
            "Turn Off Bluetooth",
            "Turn On Flashlight",
            "Turn Off Flashlight",
            "Lock Device Screen",
            "Lock Device Screen with Pin",
            "Unlock Device Screen",
            "Lock Device",
            "Vibrate Device",
            "Open File Manager in External Storage Directory",
            "Open File Manager in App Storage Directory",
            "Open File Manager in a Specific Directory",
            "Get External Storage Directory",
            "Get App Storage Directory",
            "Upload File",
            "Download File from URL",
            "Play Song from URL",
            "Play Song from File",
            "Stop Song",
            "Pause Song",
            "Resume Song",
            "Loop Song",
            "Unloop Song",
            "Show Toast Message",
            "Show Message",
            "Send SMS",
            "Start Listening for OTP",
            "Stop Listening for OTP",
            "Get Last Received SMSes",
            "Dump SMSes to File",
            "Get Clipboard Content",
            "Copy to Clipboard",
            "Get Last Called Phone Numbers",
            "Block Phone Number",
            "Unblock Phone Number",
            "Get All Blocked Phone Numbers",
            "Clear All Blocked Phone Numbers",
            "Start Recording Audio from Mic",
            "Stop Recording Audio from Mic",
            "Switch to Camera",
            "Click Image",
            "Get Device Speed",
            "Get Device Heading",
            "Start BLE Distance Tracking",
            "Get Installed Apps",
            "Click Home",
            "Click Back",
            "Click Recents",
            "Set Focused View Text",
            "Add App to Prevent Opening",
            "Remove App to Prevent Opening",
            "Clear Apps to Prevent Opening",
            "Enable Stealth Mode",
            "Disable Stealth Mode",
            "Run Shell Command",
            "Open Virtual Shell",
            "Run Python Script",
            "Run Interactive Python Script",
            "Enable Do Not Disturb",
            "Disable Do Not Disturb",
            "Get All Captured Notifications",
            "Clear All Captured Notifications",
            "Open URI",
            "Open App",
            "Restart Service",
            "Stop Service",
            "Start WiFi P2P Connection",
            "Stop WiFi P2P Connection",
            "WiFi P2P Get Host Address",
            "Start HTTP Proxy",
            "Stop HTTP Proxy",
            "Start SOCKS5 Proxy",
            "Stop SOCKS5 Proxy",
            "Open SOCKS5 Proxy Settings",
            "End Call",
            "Accept Ringing Call",
            "Set Display Brightness",
            "Add Server Phone Number",
            "Connect using SMS",
            "Connect using Internet",
            "Send Custom Command",
            "Show Command History",
            "Remove Device"
        )
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, data)
        binding.listView.adapter = adapter
        binding.textView.setOnClickListener {
            val i = Intent(this, SetDeviceIDActivity::class.java)
            i.putExtra("Position", pos)
            i.putExtra("DeviceID", currentDeviceID)
            startActivity(i)
        }
        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                adapter.filter.filter(p0)
            }

            override fun afterTextChanged(p0: Editable?) {
            }

        })
        binding.listView.setOnItemClickListener { parent, view, pos, id ->
            val position = data.indexOf(adapter.getItem(pos))
            when (position) {
                0 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("PING")
                    else
                        sendCommandBySMS("PING")
                }
                1 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_CURRENT_LOCATION")
                    else
                        sendCommandBySMS("GET_CURRENT_LOCATION")
                }
                2 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_LAST_KNOWN_LOCATION")
                    else
                        sendCommandBySMS("GET_LAST_KNOWN_LOCATION")
                }
                3 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Start Ring")
                        .setMessage("Are you sure you want to start ringing?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            if (!isUsingSMS)
                                sendCommandByFirestore("START_RING")
                            else
                                sendCommandBySMS("START_RING")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                4 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_RING")
                    else
                        sendCommandBySMS("STOP_RING")
                }
                5 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("INCREASE_VOLUME")
                    else
                        sendCommandBySMS("INCREASE_VOLUME")
                }
                6 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("DECREASE_VOLUME")
                    else
                        sendCommandBySMS("DECREASE_VOLUME")
                }
                7 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("MUTE_VOLUME")
                    else
                        sendCommandBySMS("MUTE_VOLUME")
                }
                8 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("UNMUTE_VOLUME")
                    else
                        sendCommandBySMS("UNMUTE_VOLUME")
                }
                9 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("START_BLE_ADVERTISING")
                    else
                        sendCommandBySMS("START_BLE_ADVERTISING")
                }
                10 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_BLE_ADVERTISING")
                    else
                        sendCommandBySMS("STOP_BLE_ADVERTISING")
                }
                11 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_POWER_INFO")
                    else
                        sendCommandBySMS("GET_POWER_INFO")
                }
                12 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_BATTERY_STATUS")
                    else
                        sendCommandBySMS("GET_BATTERY_STATUS")
                }
                13 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_WIFI_STATUS")
                    else
                        sendCommandBySMS("GET_WIFI_STATUS")
                }
                14 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_MOBILE_DATA_STATUS")
                    else
                        sendCommandBySMS("GET_MOBILE_DATA_STATUS")
                }
                15 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_LOCAL_NETWORK_INFO")
                    else
                        Toast.makeText(this, "GET_LOCAL_NETWORK_INFO: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                16 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("TURN_ON_WIFI")
                    else
                        sendCommandBySMS("TURN_ON_WIFI")
                }
                17 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("ASK_TO_TURN_ON_WIFI")
                    else
                        sendCommandBySMS("ASK_TO_TURN_ON_WIFI")
                }
                18 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("TURN_OFF_WIFI")
                    else
                        sendCommandBySMS("TURN_OFF_WIFI")
                }
                19 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("TURN_ON_BLUETOOTH")
                    else
                        sendCommandBySMS("TURN_ON_BLUETOOTH")
                }
                20 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("ASK_TO_TURN_ON_BLUETOOTH")
                    else
                        sendCommandBySMS("ASK_TO_TURN_ON_BLUETOOTH")
                }
                21 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("TURN_OFF_BLUETOOTH")
                    else
                        sendCommandBySMS("TURN_OFF_BLUETOOTH")
                }
                22 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Turn On Flashlight")
                        .setMessage("Are you sure you want to turn on the flash light?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            if (!isUsingSMS)
                                sendCommandByFirestore("TURN_ON_FLASHLIGHT")
                            else
                                sendCommandBySMS("TURN_ON_FLASHLIGHT")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                23 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Turn Off Flashlight")
                        .setMessage("Are you sure you want to turn off the flash light?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            if (!isUsingSMS)
                                sendCommandByFirestore("TURN_OFF_FLASHLIGHT")
                            else
                                sendCommandBySMS("TURN_OFF_FLASHLIGHT")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                24 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Lock Device Screen")
                        .setMessage("Are you sure you want to lock this device's screen?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            if (!isUsingSMS)
                                sendCommandByFirestore("LOCK_DEVICE_SCREEN")
                            else
                                sendCommandBySMS("LOCK_DEVICE_SCREEN")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                25 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding.button.text = "Lock Device Screen with Pin"
                    binding.editText.hint = "Pin"
                    binding.editText.inputType = EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
                    binding.editText.filters = arrayOf(InputFilter.LengthFilter(6))
                    binding.button.setOnClickListener {
                        val pin = binding.editText.text.toString()
                        if (pin.isEmpty()) {
                            binding.editText.error = "Please enter a pin"
                            return@setOnClickListener
                        }
                        val command = "LOCK_DEVICE_SCREEN_WITH_PIN $pin"
                        if (!isUsingSMS) {
                            sendCommandByFirestore(command)
                        } else {
                            sendCommandBySMS(command)
                        }
                        dialog.dismiss()
                    }
                    // Show the dialog
                    dialog.show()
                }
                26 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("UNLOCK_DEVICE_SCREEN")
                    else
                        sendCommandBySMS("UNLOCK_DEVICE_SCREEN")
                }
                27 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Lock Device")
                        .setMessage("Are you sure you want to lock this device?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            if (!isUsingSMS)
                                sendCommandByFirestore("LOCK_DEVICE")
                            else
                                sendCommandBySMS("LOCK_DEVICE")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                28 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("VIBRATE_DEVICE 250");
                    else
                        Toast.makeText(this, "VIBRATE_DEVICE: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                29 -> {
                    if (!isUsingSMS) {
                        val i = Intent(this, FileManagerActivity::class.java)
                        i.putExtra("Path", "<EXTERNAL_STORAGE_DIR>")
                        i.putExtra("DeviceID", currentDeviceID)
                        i.putExtra("IsInputtedByUser", false)
                        startActivity(i)
                    } else {
                        Toast.makeText(
                            this,
                            "LIST_DIR: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                30 -> {
                    if (!isUsingSMS) {
                        val i = Intent(this, FileManagerActivity::class.java)
                        i.putExtra("Path", "<APP_STORAGE_DIR>")
                        i.putExtra("DeviceID", currentDeviceID)
                        i.putExtra("IsInputtedByUser", false)
                        startActivity(i)
                    } else {
                        Toast.makeText(
                            this,
                            "LIST_DIR: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                31 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Open File Manager"
                        binding.editText.hint = "Path"
                        binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.button.setOnClickListener {
                            val path = binding.editText.text.toString()
                            if (path.isEmpty()) {
                                binding.editText.error = "Please enter the path"
                                return@setOnClickListener
                            }
                            val i = Intent(this, FileManagerActivity::class.java)
                            i.putExtra("Path", path)
                            i.putExtra("DeviceID", currentDeviceID)
                            i.putExtra("IsInputtedByUser", true)
                            startActivity(i)
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "LIST_DIR: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                32 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_EXTERNAL_STORAGE_LOCATION");
                    else
                        Toast.makeText(this, "GET_EXTERNAL_STORAGE_LOCATION: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                33 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_APP_STORAGE_DIR");
                    else
                        Toast.makeText(this, "GET_APP_STORAGE_DIR: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                34 -> {
                    if (!isUsingSMS) {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*" // Allow all file types (or specify a specific MIME type)
                        }
                        startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
                    } else {
                        Toast.makeText(
                            this,
                            "UPLOAD_FILE: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                35 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Download"
                        binding.editText.hint = "URL"
                        binding.editText2.hint = "File Path"
                        binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.editText2.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.editText2.visibility = View.VISIBLE
                        binding.button.setOnClickListener {
                            val url = binding.editText.text.toString()
                            val fileName = binding.editText2.text.toString()
                            if (url.isEmpty()) {
                                binding.editText.error = "Please enter the URL"
                                if (fileName.isEmpty())
                                    binding.editText2.error = "Please enter a file path"
                                return@setOnClickListener
                            }
                            if (fileName.isEmpty()) {
                                binding.editText2.error = "Please enter a file path"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("DOWNLOAD_FILE_FROM_URL $url <^ ^> $fileName")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(
                            this,
                            "DOWNLOAD_FILE_FROM_URL: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                36 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Play"
                        binding.editText.hint = "URL"
                        binding.button.setOnClickListener {
                            val url = binding.editText.text.toString()
                            if (url.isEmpty()) {
                                binding.editText.error = "Please enter the URL"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("PLAY_SONG_FROM_URL $url")
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(
                            this,
                            "PLAY_SONG_FROM_URL: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                37 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Play"
                        binding.editText.hint = "File Path"
                        binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.button.setOnClickListener {
                            val filePath = binding.editText.text.toString()
                            if (filePath.isEmpty()) {
                                binding.editText.error = "Please enter the file path"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("PLAY_SONG $filePath")
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(
                            this,
                            "PLAY_SONG: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                38 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_SONG")
                    else
                        Toast.makeText(this, "STOP_SONG: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                39 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("PAUSE_SONG")
                    else
                        Toast.makeText(this, "PAUSE_SONG: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                40 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("RESUME_SONG")
                    else
                        Toast.makeText(this, "RESUME_SONG: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                41 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("LOOP_SONG")
                    else
                        Toast.makeText(this, "LOOP_SONG: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                42 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("UNLOOP_SONG")
                    else
                        Toast.makeText(this, "UNLOOP_SONG: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                43 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Show"
                        binding.editText.hint = "Message"
                        binding.button.setOnClickListener {
                            val message = binding.editText.text.toString()
                            if (message.isEmpty()) {
                                binding.editText.error = "Please enter a message"
                            }
                            sendCommandByFirestore("SHOW_TOAST $message")
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "SHOW_TOAST: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                44 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    binding.button.text = "Show"
                    binding.editText.hint = "Message"
                    binding.button.setOnClickListener {
                        val message = binding.editText.text.toString()
                        if (message.isEmpty()) {
                            binding.editText.error = "Please enter a message"
                        }
                        if (!isUsingSMS)
                            sendCommandByFirestore("SHOW_MESSAGE $message")
                        else
                            sendCommandBySMS("SHOW_MESSAGE $message")
                    }
                    // Show the dialog
                    dialog.show()
                }
                45 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding2 = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding2.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding2.button.text = "Send SMS"
                        binding2.editText.hint = "Sending Phone Number"
                        binding2.editText.inputType = EditorInfo.TYPE_CLASS_PHONE
                        binding2.editText2.inputType = EditorInfo.TYPE_CLASS_PHONE
                        binding2.editText2.visibility = View.VISIBLE
                        binding2.editText2.hint = "Receiving Phone Number"
                        binding2.editText3.visibility = View.VISIBLE
                        binding2.editText3.hint = "Message"
                        binding2.button.setOnClickListener {
                            val sendingPhoneNumber = binding2.editText.text.toString()
                            if (sendingPhoneNumber.isEmpty()) {
                                binding2.editText.error = "Please enter the sending phone number"
                                return@setOnClickListener
                            }
                            val receivingPhoneNumber = binding2.editText2.text.toString()
                            if (receivingPhoneNumber.isEmpty()) {
                                binding2.editText2.error = "Please enter the sending phone number"
                                return@setOnClickListener
                            }
                            val message = binding2.editText3.text.toString()
                            if (message.isEmpty()) {
                                binding2.editText3.error = "Please enter the message"
                                return@setOnClickListener
                            }
                            if (message.length > 180) {
                                binding2.editText3.error = "Message too long"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("SEND_SMS $sendingPhoneNumber $receivingPhoneNumber $message")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "SEND_SMS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                46 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("START_LISTENING_FOR_OTP")
                    else
                        Toast.makeText(this, "START_LISTENING_FOR_OTP: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                47 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_LISTENING_FOR_OTP")
                    else
                        Toast.makeText(this, "STOP_LISTENING_FOR_OTP: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                48 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Get SMSes"
                        binding.editText.hint = "Count"
                        binding.editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
                        binding.button.setOnClickListener {
                            val count = binding.editText.text.toString()
                            if (count.isEmpty()) {
                                binding.editText.error = "Please enter a count"
                                return@setOnClickListener
                            }
                            if (!isInteger(count)) {
                                binding.editText.error = "Please enter a valid count"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("GET_LAST_N_SMS $count")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "GET_LAST_N_SMS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                49 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Dump SMSes"
                        binding.editText2.hint = "Count"
                        binding.editText2.visibility = View.VISIBLE
                        binding.editText2.inputType = EditorInfo.TYPE_CLASS_NUMBER
                        binding.editText.hint = "File Path"
                        binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.button.setOnClickListener {
                            val filePath = binding.editText.text.toString()
                            if (filePath.isEmpty()) {
                                binding.editText.error = "Please enter a file path"
                                return@setOnClickListener
                            }
                            val count = binding.editText2.text.toString()
                            if (count.isEmpty()) {
                                binding.editText2.error = "Please enter the count"
                                return@setOnClickListener
                            }
                            if (!isInteger(count)) {
                                binding.editText2.error = "Please enter a valid count"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("DUMP_LAST_N_SMS_TO_FILE $count $filePath")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "UNLOOP_SONG: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                50 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_CLIPBOARD_CONTENT")
                    else
                        Toast.makeText(this, "GET_CLIPBOARD_CONTENT: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                51 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Copy"
                        binding.editText.hint = "Text"
                        binding.button.setOnClickListener {
                            val text = binding.editText.text.toString()
                            sendCommandByFirestore("COPY_TO_CLIPBOARD $text")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "COPY_TO_CLIPBOARD: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                52 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Get Phone Numbers"
                        binding.editText.hint = "Count"
                        binding.editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
                        binding.button.setOnClickListener {
                            val count = binding.editText.text.toString()
                            if (!isInteger(count)) {
                                binding.editText.error = "Please enter a valid count"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("GET_LAST_N_PHONE_NUMBERS_CALLED $count")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "GET_LAST_N_PHONE_NUMBERS_CALLED: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                53 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Block Phone Number"
                        binding.editText.hint = "Phone Number"
                        binding.editText.inputType = EditorInfo.TYPE_CLASS_PHONE
                        binding.button.setOnClickListener {
                            val phoneNo = binding.editText.text.toString()
                            if (phoneNo.isEmpty()) {
                                binding.editText.error = "Please enter a phone number"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("BLOCK_PHONE_NUMBER $phoneNo")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "BLOCK_PHONE_NUMBER: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                54 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Unblock Phone Number"
                        binding.editText.hint = "Phone Number"
                        binding.editText.inputType = EditorInfo.TYPE_CLASS_PHONE
                        binding.button.setOnClickListener {
                            val phoneNo = binding.editText.text.toString()
                            if (phoneNo.isEmpty()) {
                                binding.editText.error = "Please enter a phone number"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("UNBLOCK_PHONE_NUMBER $phoneNo")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "UNBLOCK_PHONE_NUMBER: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                55 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_ALL_BLOCKED_NUMBERS")
                    else
                        Toast.makeText(this, "GET_ALL_BLOCKED_NUMBERS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                56 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("CLEAR_ALL_BLOCKED_NUMBERS")
                    else
                        Toast.makeText(this, "CLEAR_ALL_BLOCKED_NUMBERS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                57 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Start Recording Audio"
                        binding.editText.hint = "File Path"
                        binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.button.setOnClickListener {
                            val filePath = binding.editText.text.toString()
                            if (filePath.isEmpty()) {
                                binding.editText.error = "Please enter the file path"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("START_RECORDING_AUDIO_FROM_MIC $filePath")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "START_RECORDING_AUDIO_FROM_MIC: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                58 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_RECORDING_AUDIO_FROM_MIC")
                    else
                        Toast.makeText(this, "STOP_RECORDING_AUDIO_FROM_MIC: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                59 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Switch to Camera"
                        binding.editText.hint = "Camera Number"
                        binding.editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
                        binding.button.setOnClickListener {
                            val cameraNo = binding.editText.text.toString()
                            if (cameraNo.isEmpty()) {
                                binding.editText.error = "Please enter the camera number"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("SWITCH_TO_CAMERA $cameraNo")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "SWITCH_TO_CAMERA: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                60 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Click Image"
                        binding.editText.hint = "File Path"
                        binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.button.setOnClickListener {
                            val filePath = binding.editText.text.toString()
                            if (filePath.isEmpty()) {
                                binding.editText.error = "Please enter the file path"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("CLICK_IMAGE $filePath")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "CLICK_IMAGE: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                61 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_SPEED")
                    else
                        sendCommandBySMS("GET_SPEED")
                }
                62 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_DEVICE_HEADING")
                    else
                        sendCommandBySMS("GET_DEVICE_HEADING")
                }
                63 -> {
                    Toast.makeText(this, "This is incomplete!", Toast.LENGTH_SHORT).show()
//                    Intent(this, BLEDistanceTrackingActivity::class.java).also { intent ->
//                        intent.putExtra("DeviceID", currentDeviceID)
//                        startActivity(intent)
//                    }
                }
                64 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_INSTALLED_APPS")
                    else
                        Toast.makeText(this, "GET_INSTALLED_APPS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                65 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("CLICK_HOME")
                    else
                        Toast.makeText(this, "CLICK_HOME: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                66 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("CLICK_BACK")
                    else
                        Toast.makeText(this, "CLICK_HOME: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                67 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("CLICK_RECENTS")
                    else
                        Toast.makeText(this, "CLICK_HOME: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                68 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        binding.button.text = "Set Focused View Text"
                        binding.editText.hint = "Text"
                        binding.button.setOnClickListener {
                            val text = binding.editText.text.toString()
                            sendCommandByFirestore("SET_FOCUSED_VIEW_TEXT $text")
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "SET_FOCUSED_VIEW_TEXT: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                69 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Add App"
                        binding.editText.hint = "Package ID"
                        binding.button.setOnClickListener {
                            val packageID = binding.editText.text.toString()
                            if (packageID.isEmpty()) {
                                binding.editText.error = "Please enter the package id"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("ADD_APP_TO_PREVENT_OPENING $packageID")
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(
                            this,
                            "ADD_APP_TO_PREVENT_OPENING: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                70 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Remove App"
                        binding.editText.hint = "Package ID"
                        binding.button.setOnClickListener {
                            val packageID = binding.editText.text.toString()
                            if (packageID.isEmpty()) {
                                binding.editText.error = "Please enter the package id"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("REMOVE_APP_TO_PREVENT_OPENING $packageID")
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(
                            this,
                            "ADD_APP_TO_PREVENT_OPENING: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                71 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("CLEAR_APPS_TO_PREVENT_OPENING")
                    else
                        Toast.makeText(
                            this,
                            "ADD_APP_TO_PREVENT_OPENING: Function not available when connected using SMS",
                            Toast.LENGTH_SHORT
                        ).show()
                }
                72 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Enable Stealth Mode")
                        .setMessage("Are you sure you want to enable stealth mode?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            sendCommandByFirestore("ENABLE_STEALTH_MODE")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                73 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Disable Stealth Mode")
                        .setMessage("Are you sure you want to disable stealth mode?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            sendCommandByFirestore("DISABLE_STEALTH_MODE")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
                74 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding.button.text = "Run Command"
                    binding.editText.hint = "Command"
                    binding.button.setOnClickListener {
                        val command = binding.editText.text.toString()
                        if (command.isEmpty()) {
                            binding.editText.error = "Please enter a command"
                            return@setOnClickListener
                        }
                        sendCommandByFirestore("S_RUN_SHELL_COMMAND $command")
                    }
                    // Show the dialog
                    dialog.show()
                }
                75 -> {
                    Intent(this, VirtualShellActivity::class.java).also {
                        startActivity(it)
                    }
                }
                76 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding.button.text = "Run Python Script"
                    binding.editText.hint = "File Path"
                    binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                    binding.editText2.hint = "Arguments"
                    binding.editText2.visibility = View.VISIBLE
                    binding.button.setOnClickListener {
                        val path = binding.editText.text.toString()
                        val args = binding.editText2.text.toString()
                        if (path.isEmpty()) {
                            binding.editText.error = "Please enter the file path"
                            return@setOnClickListener
                        }
                        sendCommandByFirestore("RUN_PYTHON_SCRIPT $path <!|!> $args")
                    }
                    // Show the dialog
                    dialog.show()
                }
                77 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding.button.text = "Run Interactive Python Script"
                    binding.editText.hint = "File Path"
                    binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                    binding.editText2.hint = "Arguments"
                    binding.editText2.visibility = View.VISIBLE
                    binding.button.setOnClickListener {
                        val path = binding.editText.text.toString()
                        val args = binding.editText2.text.toString()
                        if (path.isEmpty()) {
                            binding.editText.error = "Please enter the file path"
                            return@setOnClickListener
                        }
                        Intent(this, InteractivePythonScriptActivity::class.java).also {
                            it.putExtra("FilePath", path)
                            it.putExtra("Arguments", args)
                            startActivity(it)
                        }
                        dialog.dismiss()
                    }
                    // Show the dialog
                    dialog.show()
                }
                78 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("SET_INTERRUPTION_FILTER INTERRUPTION_FILTER_NONE")
                    else
                        Toast.makeText(this, "SET_INTERRUPTION_FILTER: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                79 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("SET_INTERRUPTION_FILTER INTERRUPTION_FILTER_ALL")
                    else
                        Toast.makeText(this, "SET_INTERRUPTION_FILTER: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                80 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("GET_ALL_CAPTURED_NOTIFICATIONS")
                    else
                        Toast.makeText(this, "GET_ALL_CAPTURED_NOTIFICATIONS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                81 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("CLEAR_ALL_CAPTURED_NOTIFICATIONS")
                    else
                        Toast.makeText(this, "CLEAR_ALL_CAPTURED_NOTIFICATIONS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                }
                82 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Open URI"
                        binding.editText.hint = "URI"
                        binding.editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_URI
                        binding.button.setOnClickListener {
                            val uri = binding.editText.text.toString()
                            if (uri.isEmpty()) {
                                binding.editText.error = "Please enter the uri"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("OPEN_URI $uri")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "OPEN_URI: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                83 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Open App"
                        binding.editText.hint = "Package Name"
                        binding.button.setOnClickListener {
                            val uri = binding.editText.text.toString()
                            if (uri.isEmpty()) {
                                binding.editText.error = "Please enter the uri"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("OPEN_APP2 $uri")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "OPEN_APP2: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                84 -> {
                    if (!isUsingSMS)
                        Toast.makeText(this, "RESTART_SERVICE: Function not available when connected using internet", Toast.LENGTH_SHORT).show()
                    else
                        sendCommandBySMS("RESTART_SERVICE")
                }
                85 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_SERVICE")
                    else
                        sendCommandBySMS("STOP_SERVICE")
                }
                86 -> {
                    if (!isWifiP2pConnected) {
                        if (!isUsingSMS)
                            sendCommandByFirestore("START_WIFI_P2P_SERVER")
                        else
                            sendCommandBySMS("START_WIFI_P2P_SERVER")
                    } else {
                        Toast.makeText(this, "WIFI P2P connection is already active with $wifiP2pConnectedDeviceID", Toast.LENGTH_SHORT).show()
                    }
                }
                87 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_WIFI_P2P_SERVER")
                    else
                        sendCommandBySMS("STOP_WIFI_P2P_SERVER")
                }
                88 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("WIFI_P2P_GET_HOST_ADDRESS")
                    else
                        sendCommandBySMS("WIFI_P2P_GET_HOST_ADDRESS")
                }
                89 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding.button.text = "Start HTTP Proxy"
                    binding.editText.hint = "Port"
                    binding.editText.setText("8080")
                    binding.editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
                    binding.button.setOnClickListener {
                        val port = binding.editText.text.toString()
                        if (port.isEmpty()) {
                            binding.editText.error = "Please enter a port number"
                            return@setOnClickListener
                        }
                        if (!isUsingSMS)
                            sendCommandByFirestore("START_HTTP_PROXY $port")
                        else
                            sendCommandBySMS("START_HTTP_PROXY $port")
                        dialog.dismiss()
                    }
                    // Show the dialog
                    dialog.show()
                }
                90 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_HTTP_PROXY")
                    else
                        sendCommandBySMS("STOP_HTTP_PROXY")
                }
                91 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("START_SOCKS5_PROXY")
                    else
                        sendCommandBySMS("START_SOCKS5_PROXY")
                }
                92 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("STOP_SOCKS5_PROXY")
                    else
                        sendCommandBySMS("STOP_SOCKS5_PROXY")
                }
                93 -> {
                    if (!isUsingSMS) {
                        startActivity(Intent(this, Socks5ProxySettingsActivity::class.java).apply {
                            putExtra("DeviceID", currentDeviceID)
                        })
                    } else {
                        Toast.makeText(this, "SET_SOCKS5_PROXY_SETTINGS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                94 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("END_CALL")
                    else
                        sendCommandBySMS("END_CALL")
                }
                95 -> {
                    if (!isUsingSMS)
                        sendCommandByFirestore("ACCEPT_RINGING_CALL")
                    else
                        sendCommandBySMS("ACCEPT_RINGING_CALL")
                }
                96 -> {
                    if (!isUsingSMS) {
                        val dialog = Dialog(this)
                        val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                        dialog.setContentView(binding.root)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        binding.button.text = "Set Display Brightness"
                        binding.editText.hint = "Brightness Value [0-255]"
                        binding.editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
                        binding.button.setOnClickListener {
                            val value = binding.editText.text.toString()
                            if (value.isEmpty()) {
                                binding.editText.error = "Please enter a brightness value"
                                return@setOnClickListener
                            }
                            sendCommandByFirestore("SET_DISPLAY_BRIGHTNESS $value")
                            dialog.dismiss()
                        }
                        // Show the dialog
                        dialog.show()
                    } else {
                        Toast.makeText(this, "SET_DISPLAY_BRIGHTNESS: Function not available when connected using SMS", Toast.LENGTH_SHORT).show()
                    }
                }
                97 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding.button.text = "Add Server Phone Number"
                    binding.editText.hint = "Server Phone Number"
                    binding.editText.inputType = EditorInfo.TYPE_CLASS_PHONE
                    binding.button.setOnClickListener {
                        val phoneNo = binding.editText.text.toString()
                        if (phoneNo.isEmpty()) {
                            binding.editText.error = "Please enter a phone number"
                            return@setOnClickListener
                        }
                        if (!isUsingSMS) {
                            sendCommandByFirestore("ADD_SERVER_PHONE_NO $phoneNo")
                        } else {
                            sendCommandBySMS("ADD_SERVER_PHONE_NO $phoneNo")
                        }
                        dialog.dismiss()
                    }
                    // Show the dialog
                    dialog.show()
                }
                98 -> {
                    val dialog = Dialog(this)
                    val binding2 = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding2.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding2.button.text = "Connect by SMS"
                    binding2.editText.hint = "Phone number of $currentDeviceID"
                    binding2.editText.inputType = EditorInfo.TYPE_CLASS_PHONE
                    binding2.editText2.visibility = View.VISIBLE
                    binding2.editText2.inputType = EditorInfo.TYPE_CLASS_PHONE
                    binding2.editText2.hint = "Your phone number"
                    val phoneNumbers = preferences.getString(currentDeviceID, "")
                    var oldClientPhoneNumber: String? = null
                    var oldServerPhoneNumber: String? = null
                    if (phoneNumbers != "") {
                        oldClientPhoneNumber = phoneNumbers!!.split(",")[0]
                        oldServerPhoneNumber = phoneNumbers.split(",")[1]
                        binding2.editText.setText(oldClientPhoneNumber)
                        binding2.editText2.setText(oldServerPhoneNumber)
                    }
                    binding2.button.setOnClickListener {
                        val cPhoneNumber = binding2.editText.text.toString()
                        if (cPhoneNumber.isEmpty()) {
                            binding2.editText.error = "Please enter the phone number of $currentDeviceID"
                            return@setOnClickListener
                        }
                        if (!cPhoneNumber.startsWith("+91")) {
                            binding2.editText.error = "Please start the phone number with +91"
                            return@setOnClickListener
                        }
                        val sPhoneNumber = binding2.editText2.text.toString()
                        if (sPhoneNumber.isEmpty()) {
                            binding2.editText2.error = "Please enter your phone number"
                            return@setOnClickListener
                        }
                        if (!sPhoneNumber.startsWith("+91")) {
                            binding2.editText2.error = "Please start the phone number with +91"
                            return@setOnClickListener
                        }
                        if (cPhoneNumber == oldClientPhoneNumber && sPhoneNumber == oldServerPhoneNumber) {
                            hasClientGotPhoneNumber = true
                            hasClientGotServerPhoneNumber = true
                            isUsingSMS = true
                            binding.connectedByView.text = "Connected By: SMS"
                            clientPhoneNumber = cPhoneNumber
                            serverPhoneNumber = sPhoneNumber
                        } else {
                            sendCommandByFirestore("ADD_SERVER_PHONE_NO $sPhoneNumber")
                            sendCommandByFirestore("SET_PHONE_NO_TO_USE $cPhoneNumber")
                            serverPhoneNumber = sPhoneNumber
                            clientPhoneNumber = cPhoneNumber
                            isUsingSMS = true
                            binding.connectedByView.text = "Connected By: SMS"
                            with (preferences.edit()) {
                                putString(currentDeviceID, "$cPhoneNumber,$sPhoneNumber")
                                commit()
                            }
                        }
                        dialog.dismiss()
                    }
                    // Show the dialog
                    dialog.show()
                }
                99 -> {
                    isUsingSMS = false
                    binding.connectedByView.text = "Connected By: Internet"
                }
                100 -> {
                    val dialog = Dialog(this)
                    val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                    dialog.setContentView(binding.root)
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    binding.button.text = "Send Command"
                    binding.editText.hint = "Command"
                    binding.button.setOnClickListener {
                        val command = binding.editText.text.toString()
                        if (command.isEmpty()) {
                            binding.editText.error = "Please enter a command"
                            return@setOnClickListener
                        }
                        sendCommandByFirestore(command)
                    }
                    // Show the dialog
                    dialog.show()
                }
                101 -> {
                    Intent(this, CommandHistoryActivity::class.java).also {
                        it.putExtra("DeviceID", currentDeviceID)
                        startActivity(it)
                    }
                }
                else -> {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Delete")
                        .setMessage("Are you sure you want to remove this device?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            val data = preferences.getStringSet("Devices", null)!!.toMutableList()
                            data.removeAt(pos)
                            val idSet = preferences.getStringSet("Devices", null)!!.toMutableSet()
                            idSet.clear()
                            idSet.addAll(data)
                            with (preferences.edit()) {
                                putStringSet("Devices", idSet)
                                commit()
                            }
                            val i = Intent(this, MainActivity::class.java)
                            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(i)
                            finishAffinity()
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        }
    }

    @SuppressLint("Range")
    fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor!!.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result!!.lastIndexOf('/')
            if (cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == Activity.RESULT_OK) {
            val progressDialog = ProgressDialog(this)
            progressDialog.setMessage("Uploading file to server...")
            progressDialog.setCancelable(false)
            progressDialog.show()
            data?.data?.let { uri ->
                storage.reference.child(getFileName(uri).toString()).putFile(uri).addOnCompleteListener {
                    if (it.isSuccessful) {
                        sendCommandByFirestore("DOWNLOAD_FILE ${getFileName(uri).toString()}")
                        Toast.makeText(this, "Downloading file on remote device...", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Error: ${it.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                    progressDialog.dismiss()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        listenerReg.remove()
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
                    if (change.type == DocumentChange.Type.ADDED && (change.document.getString("For") == serverID || change.document.getString("For") == legacyDefaultServerID) && change.document.getString("From") == currentDeviceID) {
                        val message = change.document.getString("Message")
                        if (message == "PING") {
                            Toast.makeText(this, "Received ping from $currentDeviceID", Toast.LENGTH_SHORT).show()
                        } else if (message!!.startsWith("GET_CURRENT_LOCATION: ")) {
                            if (!message.startsWith("GET_CURRENT_LOCATION: Operation failed")) {
                                val i = Intent(this, LocationActivity::class.java)
                                i.putExtra(
                                    "LocationInfo",
                                    message.removePrefix("GET_CURRENT_LOCATION: ")
                                )
                                i.putExtra("DeviceID", currentDeviceID)
                                startActivity(i)
                            } else {
                                Toast.makeText(this, change.document.getString("Message"), Toast.LENGTH_LONG).show()
                            }
                        } else if (message.startsWith("GET_LAST_KNOWN_LOCATION: ")) {
                            if (!message.startsWith("GET_LAST_KNOWN_LOCATION: Operation failed")) {
                                val i = Intent(this, LocationActivity::class.java)
                                i.putExtra("LocationInfo", message.removePrefix("GET_LAST_KNOWN_LOCATION: "))
                                i.putExtra("DeviceID", currentDeviceID)
                                startActivity(i)
                            } else {
                                Toast.makeText(this, change.document.getString("Message"), Toast.LENGTH_LONG).show()
                            }
                        } else if (message.startsWith("LIST_DIR: ") || message.startsWith("GET_SOCKS5_PROXY_SETTINGS: ")) {
                        } else if (message == "SET_SERVER_PHONE_NO: Operation completed successfully") {
                            hasClientGotServerPhoneNumber = true
                            Toast.makeText(this, change.document.getString("Message"), Toast.LENGTH_SHORT).show()
                        } else if (message == "SET_PHONE_NO_TO_USE: Operation completed successfully") {
                            hasClientGotPhoneNumber = true
                            Toast.makeText(this, change.document.getString("Message"), Toast.LENGTH_SHORT).show()
                        } else if (message.startsWith("GET_INSTALLED_APPS: ")) {
                            val i = Intent(this, ShowInstalledApplicationsActivity::class.java)
                            i.putExtra("JsonString", message.removePrefix("GET_INSTALLED_APPS: "))
                            i.putExtra("DeviceID", currentDeviceID)
                            startActivity(i)
                        } else if (message.startsWith("START_WIFI_P2P_SERVER: ")) {
                            parseWifiP2pMessage(this, message)
                        } else {
                            Toast.makeText(this, change.document.getString("Message"), Toast.LENGTH_SHORT).show()
                        }
                        if (!message.startsWith("LIST_DIR: ")) {
                            firestore.collection("Messages").document(change.document.id).delete()
                                .addOnFailureListener {
                                    Toast.makeText(
                                        this,
                                        "Error: ${it.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            val err = "Error: Failed to save to history file"
                            try {
                                if (currentDeviceID != "") {
                                    val dir = File(
                                        Environment.getExternalStorageDirectory(),
                                        "ClientController"
                                    )
                                    if (!dir.exists()) {
                                        if (!dir.mkdirs()) {
                                            Toast.makeText(this, err, Toast.LENGTH_LONG)
                                                .show()
                                        }
                                    }
                                    var file = File(dir, "$currentDeviceID.txt")
                                    if (file.length() > 25000) {
                                        var name = "$currentDeviceID.1.txt"
                                        var i = 2
                                        while (File(dir, name).exists()) {
                                            name = "$currentDeviceID.$i.txt"
                                            i += 1
                                        }
                                        file.renameTo(
                                            File(
                                                dir,
                                                name
                                            )
                                        )
                                        file = File(dir, "$currentDeviceID.txt")
                                    }
                                    if (!file.exists()) {
                                        file.createNewFile()
                                    }
                                    file.appendText("Received:\n$message\n\n")
                                }
                            } catch (ex: Exception) {
                                Toast.makeText(this, ex.localizedMessage, Toast.LENGTH_LONG).show()
                                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
        listenerReg = firestore.collection("Messages").addSnapshotListener(snapshotListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiDirectBroadcastReceiver)
    }

    fun isInteger(str: String): Boolean {
        return try {
            str.toInt()
            true
        } catch (e: NumberFormatException) {
            false
        }
    }
}