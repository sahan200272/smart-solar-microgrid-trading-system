package com.example.microgridsystem

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.models.RegisterProsumerRequest
import com.example.microgridsystem.models.RegisterProsumerResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.ApiErrorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class RegisterActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tilNic: TextInputLayout
    private lateinit var etNic: TextInputEditText
    private lateinit var tilFullName: TextInputLayout
    private lateinit var etFullName: TextInputEditText
    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var tilPhone: TextInputLayout
    private lateinit var etPhone: TextInputEditText
    private lateinit var tilAddress: TextInputLayout
    private lateinit var etAddress: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var tilConfirmPassword: TextInputLayout
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnRegister: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvErrorMessage: TextView
    private lateinit var tvLoginLink: TextView
    private lateinit var tvServerConfig: TextView
    private lateinit var sessionManager: com.example.microgridsystem.util.SessionManager
    private lateinit var userDb: com.example.microgridsystem.data.UserDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        userDb = com.example.microgridsystem.data.UserDatabaseHelper.getInstance(this)
        sessionManager = com.example.microgridsystem.util.SessionManager(this)

        initViews()
        setupListeners()
        updateServerConfigText()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tilNic = findViewById(R.id.tilNic)
        etNic = findViewById(R.id.etNic)
        tilFullName = findViewById(R.id.tilFullName)
        etFullName = findViewById(R.id.etFullName)
        tilEmail = findViewById(R.id.tilEmail)
        etEmail = findViewById(R.id.etEmail)
        tilPhone = findViewById(R.id.tilPhone)
        etPhone = findViewById(R.id.etPhone)
        tilAddress = findViewById(R.id.tilAddress)
        etAddress = findViewById(R.id.etAddress)
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnRegister = findViewById(R.id.btnRegister)
        progressBar = findViewById(R.id.progressBar)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        tvLoginLink = findViewById(R.id.tvLoginLink)
        tvServerConfig = findViewById(R.id.tvServerConfig)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        tvLoginLink.setOnClickListener { finish() }
        btnRegister.setOnClickListener { attemptRegistration() }
        tvServerConfig.setOnClickListener { showServerConfigDialog() }
    }

    private fun updateServerConfigText() {
        val currentUrl = sessionManager.getBaseUrl()
        tvServerConfig.text = "Server: $currentUrl (Tap to change)"
    }

    private fun showServerConfigDialog() {
        val input = android.widget.EditText(this)
        input.setText(sessionManager.getBaseUrl())
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle("API Server URL")
            .setMessage("Set your computer's Wi-Fi IP address (e.g. http://192.168.x.x:5098/).\nLeave blank to reset to ${com.example.microgridsystem.util.SessionManager.DEFAULT_BASE_URL}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                sessionManager.setBaseUrl(input.text.toString())
                RetrofitClient.updateBaseUrl(sessionManager.getBaseUrl())
                updateServerConfigText()
                Toast.makeText(this, "Base URL updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attemptRegistration() {
        val nic = etNic.text?.toString()?.trim().orEmpty()
        val fullName = etFullName.text?.toString()?.trim().orEmpty()
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val phone = etPhone.text?.toString()?.trim().orEmpty()
        val address = etAddress.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        val confirmPassword = etConfirmPassword.text?.toString().orEmpty()

        // Clear previous errors
        tilNic.error = null
        tilFullName.error = null
        tilEmail.error = null
        tilPhone.error = null
        tilAddress.error = null
        tilPassword.error = null
        tilConfirmPassword.error = null
        tvErrorMessage.visibility = View.GONE

        var isValid = true

        if (nic.isEmpty()) {
            tilNic.error = "NIC Number is required"
            isValid = false
        } else if (nic.none { it.isDigit() } || nic.length < 8) {
            tilNic.error = "Enter a valid NIC number (e.g. 200125724376 or 987654321V), not a personal name"
            isValid = false
        }

        if (fullName.isEmpty()) {
            tilFullName.error = "Full Name is required"
            isValid = false
        }

        if (email.isEmpty()) {
            tilEmail.error = "Email is required"
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = "Please enter a valid email address"
            isValid = false
        }

        if (phone.isEmpty()) {
            tilPhone.error = "Phone number is required"
            isValid = false
        } else if (phone.length < 10) {
            tilPhone.error = "Please enter a valid phone number (at least 10 digits)"
            isValid = false
        }

        if (address.isEmpty()) {
            tilAddress.error = "Address is required"
            isValid = false
        }

        if (password.isEmpty()) {
            tilPassword.error = "Password is required"
            isValid = false
        } else if (password.length < 6) {
            tilPassword.error = "Password must be at least 6 characters"
            isValid = false
        }

        if (confirmPassword != password) {
            tilConfirmPassword.error = "Passwords do not match"
            isValid = false
        }

        if (!isValid) return

        setLoading(true)

        val request = RegisterProsumerRequest(
            nic = nic,
            fullName = fullName,
            email = email,
            phone = phone,
            address = address,
            password = password
        )

        val apiService = RetrofitClient.getService(this)
        apiService.registerProsumer(request).enqueue(object : Callback<RegisterProsumerResponse> {
            override fun onResponse(
                call: Call<RegisterProsumerResponse>,
                response: Response<RegisterProsumerResponse>
            ) {
                setLoading(false)

                if (response.isSuccessful) {
                    val body = response.body()
                    val status = body?.status ?: "Pending"

                    // Persist new prosumer registration record and status in SQLite
                    userDb.saveRegistration(
                        nic = nic,
                        fullName = fullName,
                        email = email,
                        phone = phone,
                        address = address,
                        status = status
                    )

                    showSuccessDialog(status)
                } else {
                    val errorMsg = ApiErrorUtils.parseErrorMessage(
                        response,
                        "Registration failed. Please check the provided information."
                    )
                    showError(errorMsg)
                }
            }

            override fun onFailure(call: Call<RegisterProsumerResponse>, t: Throwable) {
                setLoading(false)
                showError("Network error: ${t.localizedMessage}. Check server connection.")
            }
        })
    }

    private fun showSuccessDialog(status: String) {
        AlertDialog.Builder(this)
            .setTitle("Registration Successful")
            .setMessage("Your prosumer account has been created successfully!\n\nAccount Status: ${status.uppercase()}\n\nNote: Your account requires Backoffice activation before you can log in to access active trading and node services.")
            .setCancelable(false)
            .setPositiveButton("Go to Login") { _, _ ->
                finish()
            }
            .show()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnRegister.isEnabled = !loading
        etNic.isEnabled = !loading
        etFullName.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPhone.isEnabled = !loading
        etAddress.isEnabled = !loading
        etPassword.isEnabled = !loading
        etConfirmPassword.isEnabled = !loading
    }

    private fun showError(msg: String) {
        tvErrorMessage.text = msg
        tvErrorMessage.visibility = View.VISIBLE
    }
}
