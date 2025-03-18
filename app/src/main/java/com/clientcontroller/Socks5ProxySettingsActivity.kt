package com.clientcontroller

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivitySocks5ProxySettingsBinding
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

class Socks5ProxySettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySocks5ProxySettingsBinding
    private var deviceID: String = currentDeviceID
    private var hasGotSettings = false
    private lateinit var snapshotListener: EventListener<QuerySnapshot>
    private lateinit var listenerReg: ListenerRegistration
    private val firestore = FirebaseFirestore.getInstance()

    private val WORKERS: String = "Workers"
    private val LISTEN_ADDR: String = "ListenAddr"
    private val LISTEN_PORT: String = "ListenPort"
    private val UDP_LISTEN_ADDR: String = "UDPListenAddr"
    private val UDP_LISTEN_PORT: String = "UDPListenPort"
    private val BIND_IPV4_ADDR: String = "BindIPv4Addr"
    private val BIND_IPV6_ADDR: String = "BindIPv6Addr"
    private val BIND_IFACE: String = "BindIface"
    private val AUTH_USER: String = "AuthUser"
    private val AUTH_PASS: String = "AuthPass"
    private val LISTEN_IPV6_ONLY: String = "ListenIPv6Only"
    private val ENABLE: String = "Enable"

    private var sentMessageID: String? = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySocks5ProxySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)

            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val params = binding.main.layoutParams as ViewGroup.MarginLayoutParams
            // Subtract systemBars.bottom if necessary, so that you’re not adding extra space
            params.bottomMargin = (imeInsets.bottom - systemBars.bottom).coerceAtLeast(0)
            binding.main.layoutParams = params

            insets
        }
        deviceID = intent.getStringExtra("DeviceID")!!
        hasGotSettings = false
        binding.saveBtn.setOnClickListener {
            if (hasGotSettings) {
                val settings = mutableMapOf<String, Any?>(
                    WORKERS to binding.workersBox.text.toString().toInt(),
                    LISTEN_ADDR to binding.listenAddressBox.text.toString(),
                    LISTEN_PORT to binding.listenPortBox.text.toString().toInt(),
                    UDP_LISTEN_ADDR to binding.udpListenAddressBox.text.toString(),
                    UDP_LISTEN_PORT to binding.udpListenPortBox.text.toString().toInt(),
                    BIND_IPV4_ADDR to binding.bindIPv4AddressBox.text.toString(),
                    BIND_IPV6_ADDR to binding.bindIPv6AddressBox.text.toString(),
                    BIND_IFACE to binding.bindInterfaceBox.text.toString(),
                    AUTH_USER to binding.authUsernameBox.text.toString(),
                    AUTH_PASS to binding.authPasswordBox.text.toString(),
                    LISTEN_IPV6_ONLY to binding.listenIPv6OnlyCheckBox.isChecked,
                )
                sendCommandByFirestore("SET_SOCKS5_PROXY_SETTINGS ${Gson().toJson(settings).toString()}")
            } else {
                Toast.makeText(this, "Error: Settings from $deviceID not received", Toast.LENGTH_LONG).show()
                sentMessageID = sendCommandByFirestore("GET_SOCKS5_PROXY_SETTINGS")
            }
        }
        sentMessageID = sendCommandByFirestore("GET_SOCKS5_PROXY_SETTINGS")
    }

    override fun onStop() {
        super.onStop()
        listenerReg.remove()
    }

    override fun onStart() {
        super.onStart()
        Log.d("Socks5ProxySettingsActivity", "Set snapshot listener")
        snapshotListener = EventListener<QuerySnapshot> { snapshot, error ->
            if (error != null) {
                // Handle error
                return@EventListener
            }
            Log.d("Socks5ProxySettingsActivity", "Event listener fired")
            if (snapshot != null) {
                for (change in snapshot.documentChanges) {
                    val message = change.document.getString("Message")
                    Log.d("Socks5ProxySettingsActivity", "Got message from $deviceID: $message")
                    if (change.type == DocumentChange.Type.ADDED && change.document.getString("For") == serverID && change.document.getString("From") == deviceID) {
                        var delete = true
                        if (message?.startsWith("GET_SOCKS5_PROXY_SETTINGS: ") == true && change.document.getString("MessageID") == sentMessageID) {
                            val jsonSettings: JsonObject = Gson().fromJson(message.removePrefix("GET_SOCKS5_PROXY_SETTINGS: "), JsonObject::class.java) as JsonObject
                            binding.workersBox.setText(jsonSettings.get(WORKERS).asInt.toString())
                            binding.listenAddressBox.setText(jsonSettings.get(LISTEN_ADDR).asString)
                            binding.listenPortBox.setText(jsonSettings.get(LISTEN_PORT).asInt.toString())
                            binding.udpListenAddressBox.setText(jsonSettings.get(UDP_LISTEN_ADDR).asString)
                            binding.udpListenPortBox.setText(jsonSettings.get(UDP_LISTEN_PORT).asInt.toString())
                            binding.bindIPv4AddressBox.setText(jsonSettings.get(BIND_IPV4_ADDR).asString)
                            binding.bindIPv6AddressBox.setText(jsonSettings.get(BIND_IPV6_ADDR).asString)
                            binding.bindInterfaceBox.setText(jsonSettings.get(BIND_IFACE).asString)
                            binding.authUsernameBox.setText(jsonSettings.get(AUTH_USER).asString)
                            binding.authPasswordBox.setText(jsonSettings.get(AUTH_PASS).asString)
                            binding.listenIPv6OnlyCheckBox.isChecked = jsonSettings.get(LISTEN_IPV6_ONLY).asBoolean

                            binding.workersBox.isEnabled = true
                            binding.listenAddressBox.isEnabled = true
                            binding.listenPortBox.isEnabled = true
                            binding.udpListenAddressBox.isEnabled = true
                            binding.udpListenPortBox.isEnabled = true
                            binding.bindIPv4AddressBox.isEnabled = true
                            binding.bindIPv6AddressBox.isEnabled = true
                            binding.bindInterfaceBox.isEnabled = true
                            binding.authUsernameBox.isEnabled = true
                            binding.authPasswordBox.isEnabled = true
                            binding.listenIPv6OnlyCheckBox.isEnabled = true

                            hasGotSettings = true
                        } else if (message?.startsWith("SET_SOCKS5_PROXY_SETTINGS") == true) {
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        } else {
                            delete = false
                        }
                        if (delete) {
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
                                if (deviceID != "") {
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
                                    var file = File(dir, "$deviceID.txt")
                                    if (file.length() > 25000) {
                                        var name = "$deviceID.1.txt"
                                        var i = 2
                                        while (File(dir, name).exists()) {
                                            name = "$deviceID.$i.txt"
                                            i += 1
                                        }
                                        file.renameTo(
                                            File(
                                                dir,
                                                name
                                            )
                                        )
                                        file = File(dir, "$deviceID.txt")
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

    private fun sendCommandByFirestore(command: String): String? {
        // Generate a new document reference with an auto-generated ID
        val newDocRef = firestore.collection("Messages").document()
        val documentId = newDocRef.id

        // Prepare the data to be set in the document
        val data = hashMapOf(
            "For" to deviceID,
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
}