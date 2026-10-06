package com.example.microgridsystem

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.data.UserDatabaseHelper
import com.example.microgridsystem.models.ApiResponseMessage
import com.example.microgridsystem.models.ProsumerProfileResponse
import com.example.microgridsystem.models.UpdateProsumerRequest
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.ApiErrorUtils
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class EditProfileActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var userDb: UserDatabaseHelper

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
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvErrorMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        userDb = UserDatabaseHelper.getInstance(this)
        sessionManager = SessionManager(this)

        initViews()
        setupListeners()
        populateInitialData()
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
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)
        progressBar = findViewById(R.id.progressBar)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)

        // Enforce that NIC is strictly read-only
        etNic.isEnabled = false
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnCancel.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveProfileChanges() }
    }

    private fun populateInitialData() {
        val nic = intent.getStringExtra("EXTRA_NIC") ?: sessionManager.getNic() ?: ""
        var fullName = intent.getStringExtra("EXTRA_FULL_NAME") ?: sessionManager.getFullName() ?: ""
        var email = intent.getStringExtra("EXTRA_EMAIL") ?: ""
        var phone = intent.getStringExtra("EXTRA_PHONE") ?: ""
        var address = intent.getStringExtra("EXTRA_ADDRESS") ?: ""

        // Check local SQLite cache first for complete profile fields
        val cached = if (nic.isNotBlank()) userDb.getUserByNic(nic) else null
        if (cached != null) {
            if (fullName.isBlank()) fullName = cached.fullName.orEmpty()
            if (email.isBlank()) email = cached.email.orEmpty()
            if (phone.isBlank()) phone = cached.phone.orEmpty()
            if (address.isBlank()) address = cached.address.orEmpty()
        }

        etNic.setText(nic)
        etFullName.setText(fullName)
        etEmail.setText(email)
        etPhone.setText(phone)
        etAddress.setText(address)

        // If email or phone are empty, load fresh data from server
        if (email.isBlank() && phone.isBlank()) {
            loadFreshProfile(nic)
        }
    }

    private fun loadFreshProfile(nic: String) {
        val token = sessionManager.getAuthHeader()
        setLoading(true)

        RetrofitClient.getService(this).getProsumerProfile(token, nic)
            .enqueue(object : Callback<ProsumerProfileResponse> {
                override fun onResponse(
                    call: Call<ProsumerProfileResponse>,
                    response: Response<ProsumerProfileResponse>
                ) {
                    setLoading(false)
                    if (response.isSuccessful) {
                        val body = response.body()
                        body?.let {
                            userDb.saveOrUpdateFullProfile(it)
                            etFullName.setText(it.fullName ?: "")
                            etEmail.setText(it.email ?: "")
                            etPhone.setText(it.phone ?: "")
                            etAddress.setText(it.address ?: "")
                        }
                    }
                }

                override fun onFailure(call: Call<ProsumerProfileResponse>, t: Throwable) {
                    setLoading(false)
                }
            })
    }

    private fun saveProfileChanges() {
        val nic = etNic.text?.toString()?.trim().orEmpty()
        val fullName = etFullName.text?.toString()?.trim().orEmpty()
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val phone = etPhone.text?.toString()?.trim().orEmpty()
        val address = etAddress.text?.toString()?.trim().orEmpty()

        tilFullName.error = null
        tilEmail.error = null
        tilPhone.error = null
        tilAddress.error = null
        tvErrorMessage.visibility = View.GONE

        var isValid = true

        if (fullName.isEmpty()) {
            tilFullName.error = "Full Name cannot be empty"
            isValid = false
        }

        if (email.isEmpty()) {
            tilEmail.error = "Email address cannot be empty"
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = "Please enter a valid email address"
            isValid = false
        }

        if (phone.isEmpty()) {
            tilPhone.error = "Phone number cannot be empty"
            isValid = false
        } else if (phone.length < 10) {
            tilPhone.error = "Please enter a valid phone number (at least 10 digits)"
            isValid = false
        }

        if (address.isEmpty()) {
            tilAddress.error = "Address cannot be empty"
            isValid = false
        }

        if (!isValid) return

        setLoading(true)

        val request = UpdateProsumerRequest(
            fullName = fullName,
            email = email,
            phone = phone,
            address = address
        )

        val token = sessionManager.getAuthHeader()
        val apiService = RetrofitClient.getService(this)

        apiService.updateProsumerProfile(token, nic, request)
            .enqueue(object : Callback<ApiResponseMessage> {
                override fun onResponse(
                    call: Call<ApiResponseMessage>,
                    response: Response<ApiResponseMessage>
                ) {
                    setLoading(false)

                    if (response.isSuccessful) {
                        // Persist updated profile values directly to native SQLite database
                        userDb.updateProfile(
                            nic = nic,
                            fullName = fullName,
                            email = email,
                            phone = phone,
                            address = address
                        )
                        sessionManager.updateFullName(fullName)
                        Toast.makeText(
                            this@EditProfileActivity,
                            "Profile updated successfully.",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    } else {
                        val errorMsg = ApiErrorUtils.parseErrorMessage(
                            response,
                            "Failed to update profile. Please verify your data."
                        )
                        showError(errorMsg)
                    }
                }

                override fun onFailure(call: Call<ApiResponseMessage>, t: Throwable) {
                    setLoading(false)
                    showError("Network failure: ${t.localizedMessage}")
                }
            })
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSave.isEnabled = !loading
        etFullName.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPhone.isEnabled = !loading
        etAddress.isEnabled = !loading
    }

    private fun showError(msg: String) {
        tvErrorMessage.text = msg
        tvErrorMessage.visibility = View.VISIBLE
    }
}
