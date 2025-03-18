package com.clientcontroller

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clientcontroller.databinding.ActivityInteractivePythonScriptBinding
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import java.io.File

class InteractivePythonScriptActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInteractivePythonScriptBinding
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private lateinit var snapshotListener: EventListener<QuerySnapshot>
    private lateinit var listenerReg: ListenerRegistration
    private val messagesQueue = mutableMapOf<Int, String>()
    private var outputIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityInteractivePythonScriptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)

            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val params = binding.linearLayout.layoutParams as ViewGroup.MarginLayoutParams
            // Subtract systemBars.bottom if necessary, so that you’re not adding extra space
            params.bottomMargin = (imeInsets.bottom - systemBars.bottom).coerceAtLeast(0)
            binding.linearLayout.layoutParams = params

            insets
        }
        val filePath = intent.getStringExtra("FilePath").toString()
        val args = intent.getStringExtra("Arguments").toString()
        binding.pythonScriptInputTextBox.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                Log.d("VirtualShellActivity", "Got action: $actionId")
                val text = binding.pythonScriptInputTextBox.text.toString()
                sendCommandByFirestore("SEND_INTERACTIVE_PYTHON_SCRIPT_INPUT $text\n")
                binding.outputView.append("$text\n")
                binding.pythonScriptInputTextBox.setText("")
                binding.scrollView.post {
                    binding.scrollView.fullScroll(View.FOCUS_DOWN)
                    binding.pythonScriptInputTextBox.requestFocus()
                }
                true
            } else {
                false
            }
        }
        sendCommandByFirestore("RUN_INTERACTIVE_PYTHON_SCRIPT $filePath <!|!> $args")
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

    override fun onStop() {
        super.onStop()
        listenerReg.remove()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        sendCommandByFirestore("FORCE_STOP_INTERACTIVE_PYTHON_SCRIPT")
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
                        var delete = true
                        if (message?.startsWith("RUN_INTERACTIVE_PYTHON_SCRIPT_ERROR") == true || message?.startsWith("RUN_INTERACTIVE_PYTHON_SCRIPT:") == true) {
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        } else if (message?.startsWith("RUN_INTERACTIVE_PYTHON_SCRIPT_OUTPUT") == true) {
                            val a = message.removePrefix("RUN_INTERACTIVE_PYTHON_SCRIPT_OUTPUT: ")
                            val index = a.split(" - ")[0].toInt()
                            val text = a.removePrefix("$index - ")
                            if (index == outputIndex) {
                                binding.outputView.append(text)
                                outputIndex++
                                for (b in 1..messagesQueue.size) {
                                    for ((i, t) in messagesQueue) {
                                        if (i == outputIndex) {
                                            binding.outputView.append(t)
                                            outputIndex++
                                            messagesQueue.remove(i)
                                        }
                                    }
                                }
                            }
                            binding.scrollView.post {
                                binding.scrollView.fullScroll(View.FOCUS_DOWN)
                                binding.pythonScriptInputTextBox.requestFocus()
                            }
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
}