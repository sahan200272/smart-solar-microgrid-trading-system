// File:        operator-verify.js
// Component:   Booking Views & Grid Operator Verification
// Description: QR verification workflow for Grid Operators: scan or enter a booking QR code,
//              verify it (POST /api/reservations/verify-qr) and finalise the energy
//              transfer (PUT /api/reservations/{id}/finalize).
// Author:      Gunathilaka K.K.N.M.

// Mirrors the API rule: delivered energy may exceed the reserved amount by at most 10%.
const DELIVERY_TOLERANCE = 1.1;

// Friendly titles and next steps for the failure reasons returned by the API.
const FAILURE_DETAILS = {
    MISSING_TOKEN: {
        tone: "warning", icon: "bi-input-cursor-text", title: "No QR code entered",
        hint: "Scan the prosumer's QR code or paste its value, then verify again."
    },
    MALFORMED_TOKEN: {
        tone: "danger", icon: "bi-qr-code", title: "Not a booking QR code",
        hint: "Make sure you are scanning the booking QR code shown in the prosumer's app."
    },
    INVALID_TOKEN: {
        tone: "danger", icon: "bi-shield-x", title: "QR code not recognised",
        hint: "Ask the prosumer to reopen the booking in their app and show the latest QR code."
    },
    ALREADY_USED: {
        tone: "danger", icon: "bi-check2-all", title: "QR code already used",
        hint: "This energy transfer has already been finalised. No further action is needed."
    },
    EXPIRED: {
        tone: "danger", icon: "bi-hourglass-bottom", title: "QR code expired",
        hint: "QR codes expire 30 minutes after the booking slot ends. The prosumer will need a new booking."
    },
    NOT_APPROVED: {
        tone: "warning", icon: "bi-slash-circle", title: "Booking not approved",
        hint: "Only approved bookings can be processed at the station."
    },
    TOO_EARLY: {
        tone: "warning", icon: "bi-clock-history", title: "Too early to process",
        hint: "Codes can be processed from 30 minutes before the booking slot starts."
    },
    WRONG_NODE: {
        tone: "warning", icon: "bi-geo-alt", title: "Booking is for another node",
        hint: "Check the node selected under \"Verifying at\", or direct the prosumer to the booked station."
    },
    PROSUMER_NOT_FOUND: {
        tone: "danger", icon: "bi-person-x", title: "Prosumer account missing",
        hint: "The account linked to this booking no longer exists. Refer the prosumer to Backoffice."
    },
    PROSUMER_INACTIVE: {
        tone: "danger", icon: "bi-person-lock", title: "Prosumer account inactive",
        hint: "The prosumer must ask Backoffice to reactivate their account before the transfer."
    },
    TOKEN_MISMATCH: {
        tone: "danger", icon: "bi-arrow-left-right", title: "QR code mismatch",
        hint: "This code belongs to a different booking. Scan the prosumer's code again."
    }
};

// Used when the API gives no reason code (network, auth or server problems).
function failureForStatus(status) {
    switch (status) {
        case 0:
            return { tone: "danger", icon: "bi-wifi-off", title: "Can't reach the server",
                hint: "Check the connection and that the API is running, then try again." };
        case 401:
            return { tone: "warning", icon: "bi-person-lock", title: "Session expired", hint: "" };
        case 403:
            return { tone: "warning", icon: "bi-shield-lock", title: "Not allowed",
                hint: "Only Grid Operator accounts can verify QR codes and finalise transfers." };
        case 404:
            return { tone: "danger", icon: "bi-search", title: "Booking not found", hint: "" };
        default:
            return { tone: "danger", icon: "bi-exclamation-octagon", title: "Verification failed", hint: "" };
    }
}

// Station names by node id, used when an older booking has no station name saved.
const stationNameById = new Map();

// The booking currently on screen after a successful verification.
let verifiedBooking = null;
let isBusy = false;

let scanner = null;
let isScanning = false;

let tokenInput;
let verifyButton;
let resultPanel;

document.addEventListener("DOMContentLoaded", async () => {
    const session = opsInitPage();

    if (!session) {
        return;
    }

    if (!opsCanViewConsole(session.user.role)) {
        opsRenderRestricted("QR verification is available to Grid Operator accounts only.");
        return;
    }

    tokenInput = document.getElementById("tokenInput");
    verifyButton = document.getElementById("verifyBtn");
    resultPanel = document.getElementById("resultPanel");

    renderIdle();

    const nodeSelect = document.getElementById("nodeSelect");
    const stations = await opsLoadStations(nodeSelect);
    stations.forEach(station => stationNameById.set(station.id, station.stationName));
    nodeSelect.addEventListener("change", () => opsSaveNodeId(nodeSelect.value));

    // Verify and finalise are Grid Operator actions in the API; other roles get a read-only page.
    if (session.user.role !== "GridOperator") {
        showReadOnlyNotice();
        return;
    }

    document.getElementById("verifyForm").addEventListener("submit", event => {
        event.preventDefault();
        verifyToken(tokenInput.value);
    });

    tokenInput.addEventListener("input", () => setTokenError(""));

    document.getElementById("clearTokenBtn").addEventListener("click", () => {
        tokenInput.value = "";
        setTokenError("");
        tokenInput.focus();
    });

    // Buttons rendered inside the result panel use data-action attributes
    resultPanel.addEventListener("click", event => {
        const actionButton = event.target.closest("[data-action]");

        if (!actionButton) {
            return;
        }

        if (actionButton.dataset.action === "reset") {
            resetFlow();
        } else if (actionButton.dataset.action === "signin") {
            opsLogout();
        }
    });

    setupCamera();
    tokenInput.focus();
});

// ---------- Verification ----------

async function verifyToken(rawValue) {
    const token = rawValue.trim();

    if (!token) {
        setTokenError("Enter or scan a QR code first.");
        tokenInput.focus();
        return;
    }

    if (isBusy) {
        return;
    }

    const nodeId = document.getElementById("nodeSelect").value || null;

    isBusy = true;
    verifiedBooking = null;
    setTokenError("");
    opsSetBusy(verifyButton, true, "Verifying…");
    setStep(1);
    renderVerifying();

    try {
        const data = await opsApi("/reservations/verify-qr", {
            method: "POST",
            body: { qrToken: token, nodeId }
        });

        verifiedBooking = { token, nodeId, data };
        renderVerified(data);
        setStep(2);
    } catch (error) {
        console.warn("QR verification failed:", error);
        renderFailure(error);
    } finally {
        isBusy = false;
        opsSetBusy(verifyButton, false);
        revealResultOnSmallScreens();
    }
}

async function handleFinalize(event) {
    event.preventDefault();

    if (!verifiedBooking || isBusy) {
        return;
    }

    const booking = verifiedBooking.data;
    const deliveredInput = document.getElementById("deliveredInput");
    const reservedKWh = Number(booking.energyKWh) || 0;
    const rawDelivered = deliveredInput.value.trim();
    let deliveredKWh = null;

    // Same checks as the API, so the operator gets instant feedback.
    if (rawDelivered !== "") {
        deliveredKWh = Number(rawDelivered);

        let problem = "";

        if (!Number.isFinite(deliveredKWh) || deliveredKWh <= 0) {
            problem = "Delivered energy must be greater than zero.";
        } else if (reservedKWh > 0 && deliveredKWh > reservedKWh * DELIVERY_TOLERANCE) {
            problem = `Delivered energy can't exceed ${opsFormatNumber(reservedKWh * DELIVERY_TOLERANCE)} kWh (reserved amount + 10%).`;
        }

        if (problem) {
            deliveredInput.classList.add("is-invalid");
            document.getElementById("deliveredFeedback").textContent = problem;
            deliveredInput.focus();
            return;
        }
    }

    deliveredInput.classList.remove("is-invalid");

    const prosumerName = (booking.prosumer && (booking.prosumer.fullName || booking.prosumer.nic)) || "this prosumer";

    const confirmed = await opsConfirm({
        title: "Finalise energy transfer?",
        message: `
            <p>This marks the booking for <strong>${opsEscape(prosumerName)}</strong> as completed and uses up the QR code. This can't be undone.</p>
            <dl class="ops-confirm-summary">
                <div><dt>Station</dt><dd>${opsEscape(stationNameFor(booking))}</dd></div>
                <div><dt>Reserved</dt><dd>${opsFormatEnergy(reservedKWh)}</dd></div>
                <div><dt>Delivered</dt><dd>${deliveredKWh === null ? "Not recorded" : `${opsFormatNumber(deliveredKWh)} kWh`}</dd></div>
            </dl>`,
        confirmLabel: "Finalise transfer",
        variant: "success",
        icon: "bi-lightning-charge-fill"
    });

    if (!confirmed || !verifiedBooking) {
        return;
    }

    const finalizeButton = document.getElementById("finalizeBtn");
    const cancelButton = document.getElementById("cancelFinalizeBtn");

    isBusy = true;
    opsSetBusy(finalizeButton, true, "Finalising…");
    cancelButton.disabled = true;
    deliveredInput.disabled = true;
    document.getElementById("finalizeError").innerHTML = "";

    try {
        const result = await opsApi(`/reservations/${encodeURIComponent(booking.reservationId)}/finalize`, {
            method: "PUT",
            body: {
                qrToken: verifiedBooking.token,
                nodeId: verifiedBooking.nodeId,
                deliveredKWh
            }
        });

        renderCompleted(result.reservation || {}, booking);
        setStep(4);
        opsToast(result.message || "Energy transfer finalised successfully.", "success");

        verifiedBooking = null;
        tokenInput.value = "";
    } catch (error) {
        console.warn("Finalise failed:", error);

        // Reason codes and auth errors mean this booking can't be finalised: show the failure.
        if (error.reason || [401, 403, 404].includes(error.status)) {
            verifiedBooking = null;
            renderFailure(error);
            return;
        }

        // Otherwise (validation, network, server) keep the booking on screen so the operator can retry.
        document.getElementById("finalizeError").innerHTML = `
            <div class="ops-inline-error" role="alert">
                <i class="bi bi-exclamation-circle-fill"></i>
                <span>${opsEscape(error.message)}</span>
            </div>`;
        opsSetBusy(finalizeButton, false);
        cancelButton.disabled = false;
        deliveredInput.disabled = false;
    } finally {
        isBusy = false;
    }
}

function resetFlow() {
    verifiedBooking = null;
    tokenInput.value = "";
    setTokenError("");
    setStep(1);
    renderIdle();
    tokenInput.focus();
}

// ---------- Result panel states ----------

function renderIdle() {
    resultPanel.innerHTML = `
        <div class="ops-result-idle ops-animate">
            <span class="ops-idle-art" aria-hidden="true"><i class="bi bi-qr-code-scan"></i></span>
            <h2 class="ops-result-title">Waiting for a QR code</h2>
            <p class="ops-result-text">Scan or enter the prosumer's booking code. The booking details will appear here for you to review before finalising the transfer.</p>
            <ul class="ops-checklist">
                <li><i class="bi bi-check2-circle"></i> Codes can be processed from 30 minutes before the slot starts</li>
                <li><i class="bi bi-check2-circle"></i> Codes expire 30 minutes after the slot ends</li>
                <li><i class="bi bi-check2-circle"></i> Each code can only be used once</li>
            </ul>
        </div>`;
}

function renderVerifying() {
    resultPanel.innerHTML = `
        <div class="ops-result-idle">
            <div class="spinner-border text-primary mb-3" role="status" style="width: 2.5rem; height: 2.5rem;">
                <span class="visually-hidden">Verifying…</span>
            </div>
            <h2 class="ops-result-title">Verifying QR code…</h2>
            <p class="ops-result-text">Checking the booking with the server.</p>
        </div>`;
}

function renderVerified(booking) {
    const prosumer = booking.prosumer || {};
    const name = prosumer.fullName || prosumer.nic || "Unknown prosumer";
    const phone = prosumer.phone || "";
    const slot = opsFormatSlot(booking.slotStartTime, booking.slotEndTime);
    const phase = opsSlotPhase(booking.slotStartTime, booking.slotEndTime);
    const reservedKWh = Number(booking.energyKWh) || 0;
    const maxDelivered = reservedKWh > 0 ? reservedKWh * DELIVERY_TOLERANCE : null;

    const deliveredHelp = maxDelivered
        ? `Up to ${opsFormatNumber(maxDelivered)} kWh allowed (reserved amount + 10%). Leave blank if no meter reading is available.`
        : "Leave blank if no meter reading is available.";

    resultPanel.innerHTML = `
        <div class="ops-result-head ops-result-head--success">
            <span class="ops-result-icon" aria-hidden="true"><i class="bi bi-patch-check-fill"></i></span>
            <div class="flex-grow-1 ops-min-0">
                <h2 class="ops-result-title">QR code verified</h2>
                <p class="ops-result-text">Check the prosumer's identity, then finalise the transfer.</p>
            </div>
            ${opsStatusBadge(booking.status)}
        </div>
        <div class="ops-result-body ops-animate">
            <div class="ops-prosumer-card">
                <span class="ops-avatar-lg" aria-hidden="true">${opsEscape(opsInitials(name))}</span>
                <div class="ops-min-0">
                    <p class="ops-prosumer-name">${opsEscape(name)}</p>
                    <p class="ops-prosumer-meta">
                        <span><i class="bi bi-person-vcard"></i> ${opsEscape(prosumer.nic || "—")}</span>
                        ${phone ? `<a href="tel:${opsEscape(phone.replace(/[^\d+]/g, ""))}"><i class="bi bi-telephone"></i> ${opsEscape(phone)}</a>` : ""}
                    </p>
                </div>
            </div>

            <dl class="ops-detail-grid">
                <div><dt>Station</dt><dd>${opsEscape(stationNameFor(booking))}</dd></div>
                <div><dt>Reserved energy</dt><dd>${opsFormatEnergy(reservedKWh)}</dd></div>
                <div><dt>Slot date</dt><dd>${opsEscape(slot.date)}</dd></div>
                <div><dt>Slot time</dt><dd>${opsEscape(slot.time)}<br>${opsPhaseBadge(phase)}</dd></div>
                <div class="ops-detail-wide"><dt>Reservation ID</dt><dd><code class="ops-code">${opsEscape(booking.reservationId)}</code></dd></div>
            </dl>

            <form id="finalizeForm" class="ops-finalize" novalidate>
                <label for="deliveredInput" class="form-label">Delivered energy <span class="ops-optional">Optional</span></label>
                <div class="input-group has-validation">
                    <input type="number" inputmode="decimal" step="0.01" min="0.01" class="form-control"
                           id="deliveredInput" placeholder="${reservedKWh > 0 ? `e.g. ${opsEscape(opsFormatNumber(reservedKWh))}` : "e.g. 12.5"}"
                           aria-describedby="deliveredHelp">
                    <span class="input-group-text">kWh</span>
                    <div class="invalid-feedback" id="deliveredFeedback"></div>
                </div>
                <div class="form-text" id="deliveredHelp">${opsEscape(deliveredHelp)}</div>
                <div id="finalizeError"></div>
                <div class="ops-result-actions">
                    <button type="button" class="btn btn-outline-secondary" id="cancelFinalizeBtn" data-action="reset">Cancel</button>
                    <button type="submit" class="btn btn-success" id="finalizeBtn">
                        <i class="bi bi-lightning-charge-fill"></i> Finalise transfer
                    </button>
                </div>
            </form>
        </div>`;

    document.getElementById("finalizeForm").addEventListener("submit", handleFinalize);
    document.getElementById("deliveredInput").addEventListener("input", event => {
        event.target.classList.remove("is-invalid");
    });
}

function renderCompleted(reservation, booking) {
    const prosumer = booking.prosumer || {};
    const deliveredKWh = reservation.deliveredKWh;

    resultPanel.innerHTML = `
        <div class="ops-result-head ops-result-head--success">
            <span class="ops-result-icon" aria-hidden="true"><i class="bi bi-check-circle-fill"></i></span>
            <div class="flex-grow-1 ops-min-0">
                <h2 class="ops-result-title">Energy transfer completed</h2>
                <p class="ops-result-text">The booking was marked as completed at ${opsEscape(opsFormatTime(reservation.completedAt))} and its QR code can no longer be used.</p>
            </div>
            ${opsStatusBadge(reservation.status || "Completed")}
        </div>
        <div class="ops-result-body ops-animate">
            <dl class="ops-detail-grid">
                <div><dt>Prosumer</dt><dd>${opsEscape(prosumer.fullName || prosumer.nic || reservation.nic || "—")}</dd></div>
                <div><dt>Station</dt><dd>${opsEscape(reservation.stationName || stationNameFor(booking))}</dd></div>
                <div><dt>Delivered energy</dt><dd>${deliveredKWh == null ? "Not recorded" : opsFormatEnergy(deliveredKWh)}</dd></div>
                <div><dt>Reserved energy</dt><dd>${opsFormatEnergy(reservation.energyKWh ?? booking.energyKWh)}</dd></div>
                <div><dt>Completed at</dt><dd>${opsEscape(opsFormatDateTime(reservation.completedAt))}</dd></div>
                <div><dt>Completed by</dt><dd>${opsEscape(reservation.completedByNic || "—")}</dd></div>
                <div class="ops-detail-wide"><dt>Reservation ID</dt><dd><code class="ops-code">${opsEscape(reservation.id || booking.reservationId)}</code></dd></div>
            </dl>
            <div class="ops-result-actions">
                <a class="btn btn-outline-secondary" href="operator-dashboard.html"><i class="bi bi-speedometer2"></i> Back to dashboard</a>
                <button type="button" class="btn btn-primary" data-action="reset"><i class="bi bi-qr-code-scan"></i> Verify next code</button>
            </div>
        </div>`;
}

function renderFailure(error) {
    const details = FAILURE_DETAILS[error.reason] || failureForStatus(error.status);
    const isSessionExpired = error.status === 401;

    const action = isSessionExpired
        ? `<button type="button" class="btn btn-primary" data-action="signin"><i class="bi bi-box-arrow-in-right"></i> Sign in again</button>`
        : `<button type="button" class="btn btn-primary" data-action="reset"><i class="bi bi-arrow-repeat"></i> Scan another code</button>`;

    resultPanel.innerHTML = `
        <div class="ops-result-head ops-result-head--${details.tone}">
            <span class="ops-result-icon" aria-hidden="true"><i class="bi ${details.icon}"></i></span>
            <div class="flex-grow-1 ops-min-0">
                <h2 class="ops-result-title">${opsEscape(details.title)}</h2>
                <p class="ops-result-text">${opsEscape(error.message)}</p>
            </div>
            <span class="ops-badge ops-badge--danger ops-badge--plain"><i class="bi bi-x-lg"></i> Rejected</span>
        </div>
        <div class="ops-result-body ops-animate">
            ${details.hint ? `<div class="ops-hint"><i class="bi bi-lightbulb" aria-hidden="true"></i><p>${opsEscape(details.hint)}</p></div>` : ""}
            <div class="ops-result-actions">${action}</div>
        </div>`;

    setStep(1);
}

// Progress: 1 = scan, 2 = review, 4 = everything done.
function setStep(currentStep) {
    document.querySelectorAll("#stepper .ops-step").forEach(step => {
        const stepNumber = Number(step.dataset.step);

        step.classList.toggle("is-done", stepNumber < currentStep);
        step.classList.toggle("is-active", stepNumber === currentStep);

        if (stepNumber === currentStep) {
            step.setAttribute("aria-current", "step");
        } else {
            step.removeAttribute("aria-current");
        }
    });
}

function setTokenError(message) {
    tokenInput.classList.toggle("is-invalid", Boolean(message));
    document.getElementById("tokenFeedback").textContent = message;
}

// On stacked layouts the result is below the form, so bring it into view.
function revealResultOnSmallScreens() {
    if (window.matchMedia("(max-width: 991.98px)").matches) {
        resultPanel.scrollIntoView({ behavior: opsPrefersReducedMotion ? "auto" : "smooth", block: "start" });
    }
}

function stationNameFor(booking) {
    const station = booking.station || {};
    return station.name || booking.stationName || stationNameById.get(station.id) || "Unknown station";
}

// Backoffice users can open the page but the API only lets Grid Operators verify.
function showReadOnlyNotice() {
    document.getElementById("roleNotice").innerHTML = `
        <div class="ops-banner ops-banner--info ops-animate" role="status">
            <i class="bi bi-info-circle-fill" aria-hidden="true"></i>
            <div class="ops-banner-body">
                <p class="ops-banner-title">View only</p>
                <p class="ops-banner-text">Verifying QR codes and finalising transfers requires a Grid Operator account.</p>
            </div>
        </div>`;

    document.querySelectorAll("#verifyForm input, #verifyForm button, #cameraBtn, #nodeSelect")
        .forEach(control => { control.disabled = true; });
}

// ---------- Camera scanning ----------

function setupCamera() {
    const cameraButton = document.getElementById("cameraBtn");

    // The scanner library is loaded from a CDN; if it is missing, manual entry still works.
    if (typeof Html5Qrcode === "undefined") {
        cameraButton.disabled = true;
        showCameraMessage("Camera scanning is unavailable right now. Enter the code manually instead.");
        return;
    }

    cameraButton.addEventListener("click", () => {
        if (isScanning) {
            stopCamera();
        } else {
            startCamera();
        }
    });

    // Release the camera when the operator leaves or hides the page
    window.addEventListener("pagehide", stopCamera);
    document.addEventListener("visibilitychange", () => {
        if (document.hidden) {
            stopCamera();
        }
    });
}

async function startCamera() {
    const cameraButton = document.getElementById("cameraBtn");

    showCameraMessage("");

    // Browsers only allow camera access on HTTPS or localhost
    if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        showCameraMessage("Camera access needs a secure connection (HTTPS or localhost). Enter the code manually instead.");
        return;
    }

    if (!scanner) {
        const config = { verbose: false };

        if (typeof Html5QrcodeSupportedFormats !== "undefined") {
            config.formatsToSupport = [Html5QrcodeSupportedFormats.QR_CODE];
        }

        scanner = new Html5Qrcode("qrReader", config);
    }

    opsSetBusy(cameraButton, true, "Starting camera…");

    try {
        await scanner.start(
            { facingMode: "environment" },
            {
                fps: 10,
                qrbox: (width, height) => {
                    // The library needs at least 50px and no larger than the video frame
                    const size = Math.max(50, Math.floor(Math.min(width, height) * 0.7));
                    return { width: size, height: size };
                }
            },
            onScanSuccess,
            () => { /* Frames without a QR code are expected; ignore them. */ }
        );

        isScanning = true;
        document.getElementById("scannerBox").classList.add("is-running");
        opsSetBusy(cameraButton, false);
        cameraButton.innerHTML = `<i class="bi bi-stop-circle"></i> Stop camera`;
    } catch (error) {
        console.warn("Unable to start camera:", error);
        opsSetBusy(cameraButton, false);
        showCameraMessage(cameraErrorMessage(error));
    }
}

async function stopCamera() {
    if (!scanner || !isScanning) {
        return;
    }

    isScanning = false;

    try {
        await scanner.stop();
        scanner.clear();
    } catch (error) {
        console.warn("Unable to stop camera cleanly:", error);
    }

    document.getElementById("scannerBox").classList.remove("is-running");
    document.getElementById("cameraBtn").innerHTML = `<i class="bi bi-camera-video"></i> Start camera`;
}

async function onScanSuccess(decodedText) {
    // The library can report the same code on several frames before it stops
    if (!isScanning) {
        return;
    }

    await stopCamera();

    tokenInput.value = decodedText;
    verifyToken(decodedText);
}

function cameraErrorMessage(error) {
    const text = String((error && (error.name || error.message)) || error || "");

    if (/NotAllowed|Permission/i.test(text)) {
        return "Camera permission was denied. Allow camera access in the browser settings, or enter the code manually.";
    }

    if (/NotFound|not found|no camera/i.test(text)) {
        return "No camera was found on this device. Enter the code manually instead.";
    }

    if (/NotReadable|in use|Could not start/i.test(text)) {
        return "The camera is being used by another app. Close it and try again.";
    }

    return "The camera couldn't be started. Enter the code manually instead.";
}

function showCameraMessage(message) {
    const element = document.getElementById("cameraMessage");

    element.hidden = !message;
    element.innerHTML = message
        ? `<i class="bi bi-exclamation-triangle" aria-hidden="true"></i><span>${opsEscape(message)}</span>`
        : "";
}
