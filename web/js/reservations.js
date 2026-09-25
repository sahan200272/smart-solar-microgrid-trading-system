let currentUser = null;
let currentToken = null;
let allNodes = [];
let allReservations = [];

document.addEventListener("DOMContentLoaded", () => {
    if (!checkAuth()) return;

    setupUserInfo();
    loadNodes();
    loadReservations();

    // Event listeners
    document.getElementById("refreshBtn").addEventListener("click", loadReservations);
    document.getElementById("filterForm").addEventListener("submit", handleFilter);
    document.getElementById("resetFilterBtn").addEventListener("click", handleResetFilter);
    document.getElementById("createReservationForm").addEventListener("submit", handleCreateReservation);
    document.getElementById("updateSlotForm").addEventListener("submit", handleUpdateSlot);
});

function checkAuth() {
    currentToken = localStorage.getItem("token");
    const userStr = localStorage.getItem("user");

    if (!currentToken || !userStr) {
        window.location.href = "/pages/login.html";
        return false;
    }

    try {
        currentUser = JSON.parse(userStr);
    } catch (e) {
        window.location.href = "/pages/login.html";
        return false;
    }

    return true;
}

function setupUserInfo() {
    const userBadge = document.getElementById("userBadge");
    if (userBadge && currentUser) {
        userBadge.textContent = `${currentUser.fullName || currentUser.nic} (${currentUser.role})`;
    }

    // If Prosumer, restrict NIC inputs
    if (currentUser.role === "Prosumer") {
        const createNicInput = document.getElementById("createProsumerNic");
        if (createNicInput) {
            createNicInput.value = currentUser.nic;
            createNicInput.readOnly = true;
        }

        const nicContainer = document.getElementById("nicFilterContainer");
        if (nicContainer) {
            nicContainer.style.display = "none";
        }
    }
}

function getAuthHeaders() {
    return {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${currentToken}`
    };
}

function showAlert(message, type = "info") {
    const placeholder = document.getElementById("alertPlaceholder");
    placeholder.innerHTML = `
        <div class="alert alert-${type} alert-dismissible fade show" role="alert">
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
        </div>
    `;
    setTimeout(() => {
        const alertEl = placeholder.querySelector(".alert");
        if (alertEl) {
            alertEl.classList.remove("show");
            setTimeout(() => placeholder.innerHTML = "", 150);
        }
    }, 5000);
}

// Fetch nodes for dropdown selection
async function loadNodes() {
    try {
        const response = await fetch(`${API_BASE_URL}/nodes`, {
            headers: getAuthHeaders()
        });

        if (!response.ok) return;

        allNodes = await response.json();

        // Populate dropdowns
        const filterSelect = document.getElementById("filterNodeId");
        const createSelect = document.getElementById("createNodeId");

        filterSelect.innerHTML = `<option value="">All Stations / Nodes</option>`;
        createSelect.innerHTML = `<option value="">Choose a microgrid node...</option>`;

        allNodes.forEach(node => {
            const label = `${node.stationName} (${node.capacityKWh} kWh capacity)`;
            
            const opt1 = document.createElement("option");
            opt1.value = node.id;
            opt1.textContent = label;
            filterSelect.appendChild(opt1);

            const opt2 = document.createElement("option");
            opt2.value = node.id;
            opt2.textContent = label;
            createSelect.appendChild(opt2);
        });
    } catch (err) {
        console.warn("Unable to load microgrid nodes:", err);
    }
}

// Load reservations from API
async function loadReservations(filterParams = {}) {
    const tableBody = document.getElementById("reservationsTableBody");
    tableBody.innerHTML = `
        <tr>
            <td colspan="7" class="text-center py-4 text-muted">
                <div class="spinner-border spinner-border-sm text-primary" role="status"></div>
                Loading reservations...
            </td>
        </tr>
    `;

    try {
        let url = `${API_BASE_URL}/reservations`;
        const params = new URLSearchParams();

        if (filterParams.status) params.append("status", filterParams.status);
        if (filterParams.prosumerNic) params.append("prosumerNic", filterParams.prosumerNic);
        if (filterParams.nodeId) params.append("nodeId", filterParams.nodeId);

        const queryString = params.toString();
        if (queryString) {
            url += `?${queryString}`;
        }

        const response = await fetch(url, {
            headers: getAuthHeaders()
        });

        if (!response.ok) {
            const errData = await response.json().catch(() => ({}));
            throw new Error(errData.message || `HTTP ${response.status}`);
        }

        allReservations = await response.json();
        renderReservationsTable(allReservations);
        updateMetrics(allReservations);
    } catch (err) {
        console.error("Failed to load reservations:", err);
        tableBody.innerHTML = `
            <tr>
                <td colspan="7" class="text-center py-4 text-danger">
                    <i class="bi bi-exclamation-triangle"></i> Failed to load reservations: ${err.message}
                </td>
            </tr>
        `;
    }
}

function updateMetrics(reservations) {
    const total = reservations.length;
    let pending = 0;
    let approved = 0;
    let closed = 0;

    reservations.forEach(r => {
        const s = (r.status || "").toLowerCase();
        if (s === "pending") pending++;
        else if (s === "approved") approved++;
        else if (s === "completed" || s === "cancelled" || s === "rejected") closed++;
    });

    document.getElementById("totalCount").textContent = total;
    document.getElementById("pendingCount").textContent = pending;
    document.getElementById("approvedCount").textContent = approved;
    document.getElementById("closedCount").textContent = closed;
    document.getElementById("rowCountBadge").textContent = `${total} records`;
}

function renderReservationsTable(reservations) {
    const tableBody = document.getElementById("reservationsTableBody");
    tableBody.innerHTML = "";

    if (!reservations || reservations.length === 0) {
        tableBody.innerHTML = `
            <tr>
                <td colspan="7" class="text-center py-4 text-muted">
                    No reservations found.
                </td>
            </tr>
        `;
        return;
    }

    reservations.forEach(r => {
        const row = document.createElement("tr");

        const statusClass = `badge-${(r.status || "pending").toLowerCase()}`;
        const startTimeStr = formatDateTime(r.slotStartTime);
        const endTimeStr = formatDateTime(r.slotEndTime);

        const isOperatorOrBackoffice = currentUser.role === "Backoffice" || currentUser.role === "GridOperator";
        const isPending = r.status === "Pending";
        const isClosed = r.status === "Cancelled" || r.status === "Completed";

        let actionButtons = `
            <button class="btn btn-sm btn-outline-info" title="View Details" onclick="viewDetails('${r.id}')">
                <i class="bi bi-eye"></i>
            </button>
        `;

        // Approve button for Operators/Backoffice on pending reservations
        if (isOperatorOrBackoffice && isPending) {
            actionButtons += `
                <button class="btn btn-sm btn-success ms-1" title="Approve Reservation" onclick="handleApprove('${r.id}')">
                    <i class="bi bi-check-lg"></i> Approve
                </button>
            `;
        }

        // Reschedule button if not cancelled/completed
        if (!isClosed) {
            actionButtons += `
                <button class="btn btn-sm btn-outline-primary ms-1" title="Reschedule Slot" onclick="openUpdateModal('${r.id}', '${r.slotStartTime}', '${r.slotEndTime}')">
                    <i class="bi bi-calendar-event"></i>
                </button>
            `;

            // Cancel button
            actionButtons += `
                <button class="btn btn-sm btn-outline-danger ms-1" title="Cancel Reservation" onclick="handleCancel('${r.id}')">
                    <i class="bi bi-x-circle"></i>
                </button>
            `;
        }

        row.innerHTML = `
            <td><code class="text-muted">${r.id ? r.id.substring(0, 8) + '...' : 'N/A'}</code></td>
            <td><strong>${r.prosumerNic || 'N/A'}</strong></td>
            <td>${r.stationName || r.nodeId || 'N/A'}</td>
            <td><span class="badge bg-light text-dark border">${r.energyKWh} kWh</span></td>
            <td>
                <small class="d-block text-dark">${startTimeStr}</small>
                <small class="text-muted">to ${endTimeStr}</small>
            </td>
            <td><span class="badge ${statusClass}">${r.status}</span></td>
            <td><div class="btn-group btn-group-sm">${actionButtons}</div></td>
        `;

        tableBody.appendChild(row);
    });
}

function formatDateTime(isoString) {
    if (!isoString) return "-";
    const date = new Date(isoString);
    if (isNaN(date.getTime())) return isoString;
    return date.toLocaleString(undefined, {
        month: "short",
        day: "numeric",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit"
    });
}

function toLocalDatetimeInputValue(isoString) {
    if (!isoString) return "";
    const date = new Date(isoString);
    if (isNaN(date.getTime())) return "";
    const tzOffset = date.getTimezoneOffset() * 60000;
    const localISOTime = (new Date(date.getTime() - tzOffset)).toISOString().slice(0, 16);
    return localISOTime;
}

// Filter handling
function handleFilter(event) {
    event.preventDefault();
    const status = document.getElementById("filterStatus").value;
    const nicInput = document.getElementById("filterNic");
    const prosumerNic = nicInput ? nicInput.value.trim() : "";
    const nodeId = document.getElementById("filterNodeId").value;

    loadReservations({ status, prosumerNic, nodeId });
}

function handleResetFilter() {
    document.getElementById("filterStatus").value = "";
    const nicInput = document.getElementById("filterNic");
    if (nicInput) nicInput.value = "";
    document.getElementById("filterNodeId").value = "";
    loadReservations();
}

// Create reservation
async function handleCreateReservation(event) {
    event.preventDefault();

    const nic = currentUser.role === "Prosumer" 
        ? currentUser.nic 
        : document.getElementById("createProsumerNic").value.trim();

    const nodeId = document.getElementById("createNodeId").value;
    const energyKWh = parseFloat(document.getElementById("createEnergyKWh").value);
    const startTimeInput = document.getElementById("createSlotStartTime").value;
    const endTimeInput = document.getElementById("createSlotEndTime").value;

    if (!startTimeInput || !endTimeInput) {
        showAlert("Please select valid slot start and end times.", "warning");
        return;
    }

    const slotStartTime = new Date(startTimeInput).toISOString();
    const slotEndTime = new Date(endTimeInput).toISOString();

    if (new Date(slotEndTime) <= new Date(slotStartTime)) {
        showAlert("Slot End Time must be strictly after Slot Start Time.", "warning");
        return;
    }

    const payload = {
        prosumerNic: nic,
        nodeId: nodeId,
        energyKWh: energyKWh,
        slotStartTime: slotStartTime,
        slotEndTime: slotEndTime
    };

    const submitBtn = document.getElementById("submitCreateBtn");
    submitBtn.disabled = true;
    submitBtn.textContent = "Booking...";

    try {
        const response = await fetch(`${API_BASE_URL}/reservations`, {
            method: "POST",
            headers: getAuthHeaders(),
            body: JSON.stringify(payload)
        });

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.message || `Failed to create reservation (HTTP ${response.status})`);
        }

        // Close modal
        const modalEl = document.getElementById("createReservationModal");
        const modalInstance = bootstrap.Modal.getInstance(modalEl);
        if (modalInstance) modalInstance.hide();

        document.getElementById("createReservationForm").reset();
        showAlert("Energy slot reservation created successfully!", "success");
        loadReservations();
    } catch (err) {
        console.error("Create reservation error:", err);
        showAlert(err.message, "danger");
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = "Book Slot";
    }
}

// Approve reservation
async function handleApprove(id) {
    if (!confirm("Are you sure you want to approve this reservation?")) return;

    try {
        const response = await fetch(`${API_BASE_URL}/reservations/${id}/approve`, {
            method: "PUT",
            headers: getAuthHeaders()
        });

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.message || "Failed to approve reservation.");
        }

        showAlert("Reservation approved successfully.", "success");
        loadReservations();
    } catch (err) {
        console.error("Approve reservation error:", err);
        showAlert(err.message, "danger");
    }
}

// Cancel reservation
async function handleCancel(id) {
    if (!confirm("Are you sure you want to cancel this reservation?")) return;

    try {
        const response = await fetch(`${API_BASE_URL}/reservations/${id}/cancel`, {
            method: "PUT",
            headers: getAuthHeaders()
        });

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.message || "Failed to cancel reservation.");
        }

        showAlert("Reservation cancelled.", "info");
        loadReservations();
    } catch (err) {
        console.error("Cancel reservation error:", err);
        showAlert(err.message, "danger");
    }
}

// Open update modal
function openUpdateModal(id, slotStartTime, slotEndTime) {
    document.getElementById("updateReservationId").value = id;
    document.getElementById("updateReservationIdDisplay").value = id;
    document.getElementById("updateSlotStartTime").value = toLocalDatetimeInputValue(slotStartTime);
    document.getElementById("updateSlotEndTime").value = toLocalDatetimeInputValue(slotEndTime);

    const modal = new bootstrap.Modal(document.getElementById("updateSlotModal"));
    modal.show();
}

// Update slot times
async function handleUpdateSlot(event) {
    event.preventDefault();

    const id = document.getElementById("updateReservationId").value;
    const startTimeInput = document.getElementById("updateSlotStartTime").value;
    const endTimeInput = document.getElementById("updateSlotEndTime").value;

    const slotStartTime = new Date(startTimeInput).toISOString();
    const slotEndTime = new Date(endTimeInput).toISOString();

    if (new Date(slotEndTime) <= new Date(slotStartTime)) {
        showAlert("Slot End Time must be strictly after Slot Start Time.", "warning");
        return;
    }

    const payload = {
        slotStartTime: slotStartTime,
        slotEndTime: slotEndTime
    };

    const submitBtn = document.getElementById("submitUpdateBtn");
    submitBtn.disabled = true;
    submitBtn.textContent = "Saving...";

    try {
        const response = await fetch(`${API_BASE_URL}/reservations/${id}`, {
            method: "PUT",
            headers: getAuthHeaders(),
            body: JSON.stringify(payload)
        });

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.message || "Failed to update reservation.");
        }

        const modalEl = document.getElementById("updateSlotModal");
        const modalInstance = bootstrap.Modal.getInstance(modalEl);
        if (modalInstance) modalInstance.hide();

        showAlert("Slot schedule updated successfully!", "success");
        loadReservations();
    } catch (err) {
        console.error("Update reservation error:", err);
        showAlert(err.message, "danger");
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = "Save Changes";
    }
}

// View details modal
function viewDetails(id) {
    const reservation = allReservations.find(r => r.id === id);
    if (!reservation) return;

    const detailsBody = document.getElementById("detailsModalBody");
    detailsBody.innerHTML = `
        <table class="table table-sm">
            <tbody>
                <tr><th class="text-muted" style="width: 40%;">Reservation ID:</th><td><code>${reservation.id}</code></td></tr>
                <tr><th class="text-muted">Prosumer NIC:</th><td><strong>${reservation.prosumerNic}</strong></td></tr>
                <tr><th class="text-muted">Station Name:</th><td>${reservation.stationName || 'N/A'}</td></tr>
                <tr><th class="text-muted">Node ID:</th><td><code>${reservation.nodeId}</code></td></tr>
                <tr><th class="text-muted">Energy:</th><td><span class="badge bg-primary">${reservation.energyKWh} kWh</span></td></tr>
                <tr><th class="text-muted">Slot Start Time:</th><td>${formatDateTime(reservation.slotStartTime)}</td></tr>
                <tr><th class="text-muted">Slot End Time:</th><td>${formatDateTime(reservation.slotEndTime)}</td></tr>
                <tr><th class="text-muted">Status:</th><td><span class="badge badge-${(reservation.status || '').toLowerCase()}">${reservation.status}</span></td></tr>
                <tr><th class="text-muted">Created At:</th><td>${formatDateTime(reservation.createdAt)}</td></tr>
                <tr><th class="text-muted">Updated At:</th><td>${formatDateTime(reservation.updatedAt)}</td></tr>
                ${reservation.cancelledAt ? `<tr><th class="text-muted text-danger">Cancelled At:</th><td class="text-danger">${formatDateTime(reservation.cancelledAt)}</td></tr>` : ''}
            </tbody>
        </table>
    `;

    const modal = new bootstrap.Modal(document.getElementById("detailsModal"));
    modal.show();
}
