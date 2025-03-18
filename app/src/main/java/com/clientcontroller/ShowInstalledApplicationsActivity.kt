package com.clientcontroller

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivityShowInstalledApplicationsBinding
import com.clientcontroller.databinding.LayoutDialogBinding
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.gson.JsonParser
import java.io.File

class ShowInstalledApplicationsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShowInstalledApplicationsBinding
    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var deviceId: String
    private lateinit var listenerReg: ListenerRegistration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityShowInstalledApplicationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        deviceId = intent.getStringExtra("DeviceID").toString()
        val data = mutableListOf<String>()
        val jsonStr = intent.getStringExtra("JsonString").toString()
        val jsonArray = JsonParser.parseString(jsonStr).asJsonArray
        for (jsonElement in jsonArray) {
            val installedApp = jsonElement.asJsonArray
            val packageName = installedApp.get(1).asString
            data.add(packageName)
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, data)
        binding.installedAppsList.adapter = adapter
        binding.installedAppsList.setOnItemClickListener { parent, view, position, id ->
            val packageName = data[position]
            sendCommandByFirestore("OPEN_APP2 $packageName")
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
        binding.installedAppsList.setOnItemLongClickListener { parent, view, position, id ->
            if (position < data.size) {
                // Inflate the popup menu
                val popupMenu = PopupMenu(this, view)
                popupMenu.inflate(R.menu.content_action_menu2)

                // Set a click listener for the menu items
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.menu_item_add_app -> {
                            sendCommandByFirestore("ADD_APP_TO_PREVENT_OPENING ${data[position]}")
                            true
                        }
                        R.id.menu_item_remove_app -> {
                            sendCommandByFirestore("REMOVE_APP_TO_PREVENT_OPENING ${data[position]}")
                            true
                        }
                        else -> false
                    }
                }

                // Show the popup menu
                popupMenu.show()
                true
            } else {
                false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        listenerReg.remove()
    }

    override fun onStart() {
        super.onStart()
        listenerReg = firestore.collection("Messages").addSnapshotListener { snapshot, error ->
            if (error != null) {
                // Handle error
                Toast.makeText(this, "Error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                return@addSnapshotListener
            }
            if (snapshot != null) {
                for (change in snapshot.documentChanges) {
                    if ((change.document.getString("For") == serverID || change.document.getString("For") == legacyDefaultServerID)
                        && change.document.getString("From") == deviceId
                        && change.type == DocumentChange.Type.ADDED) {
                        val message = change.document.getString("Message")
                        if (message!!.startsWith("OPEN_APP2: ") || message.startsWith("ADD_APP_TO_PREVENT_OPENING: ") || message.startsWith("REMOVE_APP_TO_PREVENT_OPENING: ")) {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            firestore.collection("Messages").document(change.document.id).delete().addOnFailureListener {
                                Toast.makeText(this, "Error: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
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
}