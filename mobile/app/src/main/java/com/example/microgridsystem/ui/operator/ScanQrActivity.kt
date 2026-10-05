// File:        ScanQrActivity.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Operator Mode scanner. Reads the prosumer's booking QR code with the camera
//              (or a code typed / pasted by hand) and opens VerifyBookingActivity, which
//              checks it with the server. Camera scanning uses ZXing Android Embedded
//              (Apache 2.0, https://github.com/journeyapps/zxing-android-embedded).
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.ui.operator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.microgridsystem.R
import com.example.microgridsystem.data.OpsCacheDb
import com.example.microgridsystem.data.OpsPrefs
import com.example.microgridsystem.models.OperatorStation
import com.example.microgridsystem.network.RetrofitClient
import com.example.microgridsystem.util.SessionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ScanQrActivity : AppCompatActivity() {

    companion object {
        private const val STATE_PERMISSION_REQUESTED = "permission_requested"

        // Optional "SSMG:1:" prefix and a Base64Url token, the same check the API makes.
        private val BOOKING_CODE = Regex("^(SSMG:1:)?[A-Za-z0-9_-]{1,256}$")

        // Removes spaces and line breaks that a paste or handheld scanner may add.
        fun normaliseCode(raw: String): String = raw.replace(Regex("\\s+"), "")

        // True when the value looks like a booking QR code. The server still validates it.
        fun isBookingCode(code: String): Boolean = BOOKING_CODE.matches(code)
    }

    private lateinit var session: SessionManager
    private lateinit var cacheDb: OpsCacheDb

    private lateinit var scanRoot: View
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvNodeChip: TextView
    private lateinit var tvScanHint: TextView
    private lateinit var progressCamera: CircularProgressIndicator
    private lateinit var cardNotice: MaterialCardView
    private lateinit var ivNoticeIcon: ImageView
    private lateinit var tvNoticeTitle: TextView
    private lateinit var tvNoticeMessage: TextView
    private lateinit var btnNoticeAction: MaterialButton
    private lateinit var scanTopBar: LinearLayout
    private lateinit var scanPanel: LinearLayout
    private lateinit var btnManualEntry: MaterialButton

    private var stations: List<OperatorStation> = emptyList()
    private var selectedNodeId: String? = null
    private var stationsCall: Call<List<OperatorStation>>? = null

    // True once the screen is set up for a Grid Operator on a device with a camera.
    private var canScan = false
    // Set while a code is being handed to the verification screen, to ignore repeat reads.
    private var isHandlingResult = false
    private var isOverlayOpen = false
    private var lastRejectedCode: String? = null
    private var permissionRequested = false
    private var isTorchOn = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startScanning() else showPermissionNotice()
        }

    // Sets up the scanner, node selector and manual entry.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpsUi.enableEdgeToEdge(this, lightBars = false)
        setContentView(R.layout.activity_scan_qr)

        session = SessionManager(this)
        if (!session.isLoggedIn()) {
            OpsUi.goToLogin(this)
            return
        }

        cacheDb = OpsCacheDb(this)
        permissionRequested = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUESTED) ?: false

        bindViews()
        applyInsets()
        setupToolbar()
        setupScanner()

        btnManualEntry.setOnClickListener { showManualEntrySheet() }
        tvNodeChip.setOnClickListener { showNodePicker() }

        // Verification is a Grid Operator action in the API, so other roles can't scan.
        if (!OpsUi.isGridOperator(session.getRole())) {
            tvNodeChip.isVisible = false
            tvScanHint.isVisible = false
            scanPanel.isVisible = false
            showNotice(
                R.drawable.ic_lock, R.string.ops_scan_restricted_title, R.string.ops_scan_restricted_message,
                R.string.ops_action_go_back
            ) { finish() }
            return
        }

        selectedNodeId = OpsPrefs.getNodeId(this)
        loadStations()

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            tvScanHint.isVisible = false
            showNotice(
                R.drawable.ic_ops_camera_off, R.string.ops_camera_missing_title, R.string.ops_camera_missing_message,
                R.string.ops_scan_manual_button
            ) { showManualEntrySheet() }
            return
        }

        canScan = true
    }

    // Starts (or asks permission for) the camera each time the screen comes back.
    override fun onResume() {
        super.onResume()

        if (!canScan) {
            return
        }

        isHandlingResult = false

        when {
            hasCameraPermission() -> startScanning()
            !permissionRequested -> {
                permissionRequested = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> showPermissionNotice()
        }
    }

    // Releases the camera while the screen is hidden.
    override fun onPause() {
        super.onPause()

        if (::barcodeView.isInitialized) {
            barcodeView.pause()
        }

        isTorchOn = false
        updateTorchIcon()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permissionRequested)
    }

    override fun onDestroy() {
        stationsCall?.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        scanRoot = findViewById(R.id.scanRoot)
        barcodeView = findViewById(R.id.barcodeScanner)
        toolbar = findViewById(R.id.scanToolbar)
        tvNodeChip = findViewById(R.id.tvNodeChip)
        tvScanHint = findViewById(R.id.tvScanHint)
        progressCamera = findViewById(R.id.progressCamera)
        cardNotice = findViewById(R.id.cardNotice)
        ivNoticeIcon = findViewById(R.id.ivNoticeIcon)
        tvNoticeTitle = findViewById(R.id.tvNoticeTitle)
        tvNoticeMessage = findViewById(R.id.tvNoticeMessage)
        btnNoticeAction = findViewById(R.id.btnNoticeAction)
        scanTopBar = findViewById(R.id.scanTopBar)
        scanPanel = findViewById(R.id.scanPanel)
        btnManualEntry = findViewById(R.id.btnManualEntry)
    }

    // The camera fills the screen; only the top bar and bottom panel avoid the system bars.
    private fun applyInsets() {
        val topPadding = scanTopBar.paddingTop
        val panelBottom = scanPanel.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(scanRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())

            scanTopBar.setPadding(bars.left, topPadding + bars.top, bars.right, scanTopBar.paddingBottom)
            scanPanel.setPadding(scanPanel.paddingLeft, scanPanel.paddingTop, scanPanel.paddingRight, panelBottom + bars.bottom)

            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }

        // Only offer the torch on devices that have a flash
        toolbar.menu.findItem(R.id.action_ops_torch)?.isVisible =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_ops_torch) {
                if (isTorchOn) barcodeView.setTorchOff() else barcodeView.setTorchOn()
                true
            } else {
                false
            }
        }
    }

    // Configures the ZXing view to read QR codes only and report camera state.
    private fun setupScanner() {
        barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        barcodeView.setStatusText("")

        barcodeView.setTorchListener(object : DecoratedBarcodeView.TorchListener {
            override fun onTorchOn() {
                isTorchOn = true
                updateTorchIcon()
            }

            override fun onTorchOff() {
                isTorchOn = false
                updateTorchIcon()
            }
        })

        barcodeView.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() {}

            override fun previewStarted() {
                progressCamera.isVisible = false
            }

            override fun previewStopped() {}

            override fun cameraError(error: Exception) {
                progressCamera.isVisible = false
                tvScanHint.isVisible = false
                showNotice(
                    R.drawable.ic_ops_camera_off, R.string.ops_camera_error_title, R.string.ops_camera_error_message,
                    R.string.ops_scan_manual_button
                ) { showManualEntrySheet() }
            }

            override fun cameraClosed() {}
        })

        barcodeView.decodeContinuous { result -> onCodeScanned(result.text) }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    // Shows the live preview and starts decoding.
    private fun startScanning() {
        cardNotice.isVisible = false
        tvScanHint.isVisible = true
        progressCamera.isVisible = true

        if (!isOverlayOpen) {
            barcodeView.resume()
        }
    }

    // Resumes decoding after a dialog or sheet closes, unless a code is already being handled.
    private fun resumeIfIdle() {
        isOverlayOpen = false

        if (canScan && !isHandlingResult && hasCameraPermission() && !cardNotice.isVisible) {
            barcodeView.resume()
        }
    }

    // Explains why the camera is off: first refusal, or "don't ask again" (needs Settings).
    private fun showPermissionNotice() {
        progressCamera.isVisible = false
        tvScanHint.isVisible = false

        val blocked = permissionRequested &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

        if (blocked) {
            showNotice(
                R.drawable.ic_ops_camera_off, R.string.ops_camera_blocked_title, R.string.ops_camera_blocked_message,
                R.string.ops_action_open_settings
            ) { openAppSettings() }
        } else {
            showNotice(
                R.drawable.ic_ops_camera_off, R.string.ops_camera_needed_title, R.string.ops_camera_needed_message,
                R.string.ops_action_allow_camera
            ) { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
        }
    }

    // Replaces the preview with a notice card and one action.
    private fun showNotice(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes message: Int,
        @StringRes actionLabel: Int,
        onAction: () -> Unit
    ) {
        barcodeView.pause()
        ivNoticeIcon.setImageResource(icon)
        tvNoticeTitle.setText(title)
        tvNoticeMessage.setText(message)
        btnNoticeAction.setText(actionLabel)
        btnNoticeAction.setOnClickListener { onAction() }
        cardNotice.isVisible = true
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun updateTorchIcon() {
        val item = if (::toolbar.isInitialized) toolbar.menu.findItem(R.id.action_ops_torch) else null
        item ?: return

        item.setIcon(if (isTorchOn) R.drawable.ic_ops_flash_on else R.drawable.ic_ops_flash_off)
        item.setTitle(if (isTorchOn) R.string.ops_scan_torch_off else R.string.ops_scan_torch_on)
    }

    // Handles a decoded QR code. Codes that aren't booking codes are ignored with a message.
    private fun onCodeScanned(rawValue: String?) {
        if (isHandlingResult || rawValue.isNullOrBlank()) {
            return
        }

        val code = normaliseCode(rawValue)

        if (!isBookingCode(code)) {
            // The camera keeps reading the same code, so only show the message once per code
            if (code != lastRejectedCode) {
                lastRejectedCode = code
                Snackbar.make(scanRoot, R.string.ops_scan_not_booking_code, Snackbar.LENGTH_LONG)
                    .setAnchorView(scanPanel)
                    .show()
            }
            return
        }

        isHandlingResult = true
        barcodeView.pause()
        barcodeView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        openVerification(code)
    }

    private fun openVerification(code: String) {
        startActivity(
            Intent(this, VerifyBookingActivity::class.java)
                .putExtra(VerifyBookingActivity.EXTRA_QR_VALUE, code)
                .putExtra(VerifyBookingActivity.EXTRA_NODE_ID, selectedNodeId)
        )
    }

    // Bottom sheet for typing or pasting a booking code.
    private fun showManualEntrySheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = layoutInflater.inflate(R.layout.sheet_manual_code, null)
        val tilCode = sheet.findViewById<TextInputLayout>(R.id.tilManualCode)
        val etCode = sheet.findViewById<TextInputEditText>(R.id.etManualCode)
        val btnVerify = sheet.findViewById<MaterialButton>(R.id.btnManualVerify)

        // Validates the typed code, then hands it to the verification screen
        fun submit() {
            val code = normaliseCode(etCode.text?.toString().orEmpty())

            if (code.isEmpty()) {
                tilCode.error = getString(R.string.ops_manual_error_required)
                return
            }

            if (!isBookingCode(code)) {
                tilCode.error = getString(R.string.ops_manual_error_format)
                return
            }

            isHandlingResult = true
            dialog.dismiss()
            openVerification(code)
        }

        etCode.doAfterTextChanged { tilCode.error = null }
        etCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
        btnVerify.setOnClickListener { submit() }

        dialog.setContentView(sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // Open the keyboard with the sheet and keep the sheet above it. ADJUST_RESIZE is deprecated
        // for edge-to-edge activities, but dialog windows still rely on it to move above the keyboard.
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.setOnShowListener { etCode.requestFocus() }
        dialog.setOnDismissListener { resumeIfIdle() }

        // Stop decoding behind the sheet so a stray read doesn't navigate away mid-typing
        isOverlayOpen = true
        if (canScan) {
            barcodeView.pause()
        }

        dialog.show()
    }

    // Loads the stations for the "Verifying at" selector: cached copy first, then the API.
    private fun loadStations() {
        stations = cacheDb.getStations()
        updateNodeChip()

        stationsCall = RetrofitClient.getService(this).getOperatorStations(session.getAuthHeader())
        stationsCall?.enqueue(object : Callback<List<OperatorStation>> {
            override fun onResponse(call: Call<List<OperatorStation>>, response: Response<List<OperatorStation>>) {
                if (isFinishing || isDestroyed) return

                val body = response.body()
                if (response.isSuccessful && body != null) {
                    stations = body.filter { !it.id.isNullOrBlank() }
                        .sortedBy { (it.stationName ?: "").lowercase() }
                    cacheDb.replaceStations(stations)

                    // Forget a saved node that has since been deactivated
                    if (selectedNodeId != null && stations.none { it.id == selectedNodeId }) {
                        selectedNodeId = null
                        OpsPrefs.setNodeId(this@ScanQrActivity, null)
                    }
                }

                updateNodeChip()
            }

            override fun onFailure(call: Call<List<OperatorStation>>, error: Throwable) {
                if (call.isCanceled || isFinishing || isDestroyed) return
                updateNodeChip()
            }
        })
    }

    private fun updateNodeChip() {
        val station = stations.firstOrNull { it.id == selectedNodeId }
        val label = station?.stationName ?: getString(R.string.ops_node_all_short)

        tvNodeChip.text = getString(R.string.ops_scan_verifying_at, label)
        tvNodeChip.contentDescription = getString(R.string.ops_scan_node_description, label)
    }

    // Lets the operator choose the node they are working at, so bookings for other nodes are rejected.
    private fun showNodePicker() {
        if (stations.isEmpty()) {
            Snackbar.make(scanRoot, R.string.ops_nodes_unavailable, Snackbar.LENGTH_LONG)
                .setAnchorView(scanPanel)
                .setAction(R.string.ops_action_retry) { loadStations() }
                .show()
            return
        }

        val labels = arrayOf(getString(R.string.ops_node_any)) +
            stations.map { it.stationName ?: it.id.orEmpty() }.toTypedArray()
        val checked = stations.indexOfFirst { it.id == selectedNodeId }.let { if (it >= 0) it + 1 else 0 }

        isOverlayOpen = true
        if (canScan) {
            barcodeView.pause()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ops_node_picker_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectedNodeId = if (which == 0) null else stations[which - 1].id
                OpsPrefs.setNodeId(this, selectedNodeId)
                updateNodeChip()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.ops_action_cancel, null)
            .setOnDismissListener { resumeIfIdle() }
            .show()
    }
}
