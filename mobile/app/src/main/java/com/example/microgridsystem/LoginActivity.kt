package com.example.microgridsystem

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.data.UserDatabaseHelper
import com.example.microgridsystem.models.LoginRequest
import com.example.microgridsystem.models.LoginResponse
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.ApiErrorUtils
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    private lateinit var tilNic: TextInputLayout
    private lateinit var etNic: TextInputEditText
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvErrorMessage: TextView
    private lateinit var tvRegisterLink: TextView
    private lateinit var tvServerConfig: TextView

    private lateinit var sessionManager: SessionManager
    private lateinit var userDb: UserDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        userDb = UserDatabaseHelper.getInstance(this)
        sessionManager = SessionManager(this)

        // Check if user is already logged in
        if (sessionManager.isLoggedIn()) {
            navigateAfterLogin(sessionManager.getStatus() ?: "Active")
            return
        }

        initViews()
        setupListeners()
        updateServerConfigText()
    }

    private fun initViews() {
        tilNic = findViewById(R.id.tilNic)
        etNic = findViewById(R.id.etNic)
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        tvRegisterLink = findViewById(R.id.tvRegisterLink)
        tvServerConfig = findViewById(R.id.tvServerConfig)
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            attemptLogin()
        }

        tvRegisterLink.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        tvServerConfig.setOnClickListener {
            showServerConfigDialog()
        }
    }

    private fun updateServerConfigText() {
        val currentUrl = sessionManager.getBaseUrl()
        tvServerConfig.text = "Server: $currentUrl (Tap to change)"
    }

    private fun showServerConfigDialog() {
        val input = EditText(this)
        input.setText(sessionManager.getBaseUrl())
        input.setSelection(input.text.length)

        AlertDialog.Builder(this)
            .setTitle("API Server URL")
            .setMessage("For Android Emulator use: http://10.0.2.2:5098/\nFor Physical Phone use your PC IP: http://192.168.x.x:5098/\nLeave blank to reset to ${SessionManager.DEFAULT_BASE_URL}")
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

    private fun attemptLogin() {
        val nic = etNic.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()

        tilNic.error = null
        tilPassword.error = null
        tvErrorMessage.visibility = View.GONE

        // Input validation
        var isValid = true
        if (nic.isEmpty()) {
            tilNic.error = "NIC is required"
            isValid = false
        }

        if (password.isEmpty()) {
            tilPassword.error = "Password is required"
            isValid = false
        }

        if (!isValid) return

        setLoading(true)

        val request = LoginRequest(nic = nic, password = password)
        val apiService = RetrofitClient.getService(this)

        apiService.login(request).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                setLoading(false)

                if (response.isSuccessful) {
                    val body = response.body()
                    val token = body?.token
                    val user = body?.user

                    if (!token.isNullOrBlank() && user != null) {
                        val userNic = user.nic ?: nic
                        val fullName = user.fullName ?: "Prosumer"
                        val role = user.role ?: "Prosumer"
                        val status = user.status ?: "Active"

                        // Persist login credentials, token, role, and status into local SQLite database
                        userDb.saveLoginSession(
                            nic = userNic,
                            token = token,
                            fullName = fullName,
                            role = role,
                            status = status
                        )

                        // Save session details locally
                        sessionManager.saveLoginSession(
                            token = token,
                            nic = userNic,
                            fullName = fullName,
                            role = role,
                            status = status
                        )

                        Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                        navigateAfterLogin(status)
                    } else {
                        showError("Invalid server response. Please try again.")
                    }
                } else {
                    // Check error code and message
                    val rawMsg = ApiErrorUtils.parseErrorMessage(response, "Login failed. Check your credentials.")
                    val friendlyMsg = when {
                        rawMsg.contains("pending", ignoreCase = true) ->
                            "Account is Pending: This prosumer account is waiting for Backoffice approval. Please activate the account via the Backoffice web dashboard before logging in."
                        rawMsg.contains("deactivated", ignoreCase = true) ->
                            "Account is Deactivated: Your account has been deactivated. Self-reactivation is not permitted. Contact Backoffice support."
                        else -> rawMsg
                    }
                    showError(friendlyMsg)
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                setLoading(false)
                showError("Unable to connect to server: ${t.localizedMessage}. Check server IP and network.")
            }
        })
    }

    private fun navigateAfterLogin(status: String) {
        val role = sessionManager.getRole() ?: "Prosumer"
        val intent = when {
            role.equals("GridOperator", ignoreCase = true) || role.equals("Grid Operator", ignoreCase = true) -> {
                Intent(this, GridOperatorDashboardActivity::class.java)
            }
            status.equals("Active", ignoreCase = true) -> {
                Intent(this, ProsumerDashboardActivity::class.java)
            }
            else -> {
                Intent(this, AccountStatusActivity::class.java)
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
        etNic.isEnabled = !loading
        etPassword.isEnabled = !loading
    }

    private fun showError(msg: String) {
        tvErrorMessage.text = msg
        tvErrorMessage.visibility = View.VISIBLE
    }
}
