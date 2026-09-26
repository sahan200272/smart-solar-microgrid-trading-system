// File:        operator-dashboard.js
// Component:   Booking Views & Grid Operator Verification
// Description: Loads the Grid Operator dashboard (booking counts, today's bookings and
//              pending approvals) from GET /api/operator/dashboard.
// Author:      Gunathilaka K.K.N.M.

// Maps each KPI element to its field in the API "counts" object.
const KPI_FIELDS = [
    { elementId: "kpiPending", field: "pendingCount" },
    { elementId: "kpiUpcoming", field: "approvedFutureCount" },
    { elementId: "kpiToday", field: "activeTodayCount" },
    { elementId: "kpiCompleted", field: "completedTodayCount" },
    { elementId: "kpiCancelled", field: "cancelledTodayCount" }
];

const TABLE_COLUMNS = 5;
const SKELETON_ROWS = 3;

// Station names by node id, used when an older booking has no station name saved.
const stationNameById = new Map();

let hasLoadedOnce = false;
let isLoading = false;

document.addEventListener("DOMContentLoaded", async () => {
    const session = opsInitPage();

    if (!session) {
        return;
    }

    if (!opsCanViewConsole(session.user.role)) {
        opsRenderRestricted("The operator console is available to Grid Operator and Backoffice accounts only.");
        return;
    }

    const nodeFilter = document.getElementById("nodeFilter");

    document.getElementById("refreshBtn").addEventListener("click", () => loadDashboard({ keepContent: true }));

    nodeFilter.addEventListener("change", () => {
        opsSaveNodeId(nodeFilter.value);
        loadDashboard({ keepContent: false });
    });

    renderTableSkeletons();

    const stations = await opsLoadStations(nodeFilter);
    stations.forEach(station => stationNameById.set(station.id, station.stationName));

    loadDashboard({ keepContent: false });
});

// Fetches the dashboard for the selected node. With keepContent the current data stays
// visible (dimmed) while refreshing, instead of being replaced by skeletons.
async function loadDashboard({ keepContent }) {
    if (isLoading) {
        return;
    }

    isLoading = true;

    const nodeId = document.getElementById("nodeFilter").value;
    const refreshButton = document.getElementById("refreshBtn");
    const contentSections = document.querySelectorAll(".ops-kpi-grid, .ops-card");

    refreshButton.disabled = true;
    document.getElementById("refreshIcon").classList.add("ops-spin");

    if (keepContent && hasLoadedOnce) {
        contentSections.forEach(section => section.classList.add("ops-is-refreshing"));
    } else {
        renderTableSkeletons();
    }

    try {
        const query = nodeId ? `?nodeId=${encodeURIComponent(nodeId)}` : "";
        const data = await opsApi(`/operator/dashboard${query}`);
        const counts = data.counts || {};

        document.getElementById("dashboardAlert").innerHTML = "";

        renderCounts(counts);
        renderTodayBookings(data.todayBookings || [], counts.activeTodayCount);
        renderPendingReservations(data.pendingReservations || [], counts.pendingCount);
        renderMeta(data.generatedAt, nodeId);

        if (keepContent && hasLoadedOnce) {
            opsToast("Dashboard is up to date.", "success");
        }

        hasLoadedOnce = true;
    } catch (error) {
        console.error("Failed to load operator dashboard:", error);
        renderLoadError(error);
    } finally {
        isLoading = false;
        refreshButton.disabled = false;
        document.getElementById("refreshIcon").classList.remove("ops-spin");
        contentSections.forEach(section => section.classList.remove("ops-is-refreshing"));
    }
}

function renderCounts(counts) {
    KPI_FIELDS.forEach(({ elementId, field }) => {
        opsAnimateCount(document.getElementById(elementId), counts[field]);
    });
}

function renderMeta(generatedAt, nodeId) {
    const meta = document.getElementById("dashboardMeta");
    const scopeLabel = nodeId
        ? stationNameById.get(nodeId) || "Selected node"
        : "All microgrid nodes";

    meta.classList.remove("is-stale");
    document.getElementById("lastUpdated").textContent = `Updated ${opsFormatTime(generatedAt)}`;
    document.getElementById("nodeScope").textContent = scopeLabel;
}

function renderTodayBookings(bookings, totalCount) {
    document.getElementById("todayCount").textContent = (totalCount ?? bookings.length).toLocaleString("en-US");

    const body = document.getElementById("todayBody");

    if (bookings.length === 0) {
        body.innerHTML = fullWidthRow(opsEmptyState({
            icon: "bi-calendar2-x",
            title: "No bookings scheduled for today",
            text: "Approved bookings for today will appear here, earliest slot first."
        }));
    } else {
        body.innerHTML = bookings.map(booking => {
            const slot = opsFormatSlot(booking.slotStartTime, booking.slotEndTime);
            const phase = opsSlotPhase(booking.slotStartTime, booking.slotEndTime);
            const qrBadge = booking.qrIssued
                ? `<span class="ops-badge ops-badge--info ops-badge--plain"><i class="bi bi-qr-code"></i> QR issued</span>`
                : `<span class="ops-badge ops-badge--neutral ops-badge--plain"><i class="bi bi-dash-circle"></i> Not issued</span>`;

            return `
                <tr>
                    <td class="ops-td-primary" data-label="Prosumer">${personCell(booking)}</td>
                    <td data-label="Station"><span class="ops-cell-main">${opsEscape(stationName(booking))}</span></td>
                    <td data-label="Time">
                        <div>
                            <div class="ops-cell-main">${opsEscape(slot.time)}</div>
                            <div class="ops-cell-sub">${opsPhaseBadge(phase)}</div>
                        </div>
                    </td>
                    <td data-label="Energy" class="text-md-end"><span class="ops-num">${opsFormatEnergy(booking.energyKWh)}</span></td>
                    <td data-label="QR code">${qrBadge}</td>
                </tr>`;
        }).join("");
    }

    renderPreviewFooter("todayFooter", bookings.length, totalCount, "today's bookings");
}

function renderPendingReservations(reservations, totalCount) {
    document.getElementById("pendingCount").textContent = (totalCount ?? reservations.length).toLocaleString("en-US");

    const body = document.getElementById("pendingBody");

    if (reservations.length === 0) {
        body.innerHTML = fullWidthRow(opsEmptyState({
            tone: "success",
            icon: "bi-check2-all",
            title: "All caught up",
            text: "There are no booking requests waiting for approval."
        }));
    } else {
        body.innerHTML = reservations.map(reservation => {
            const slot = opsFormatSlot(reservation.slotStartTime, reservation.slotEndTime);

            return `
                <tr>
                    <td class="ops-td-primary" data-label="Prosumer">${personCell(reservation)}</td>
                    <td data-label="Station"><span class="ops-cell-main">${opsEscape(stationName(reservation))}</span></td>
                    <td data-label="Requested slot">
                        <div>
                            <div class="ops-cell-main">${opsEscape(slot.date)}</div>
                            <div class="ops-cell-sub">${opsEscape(slot.time)}</div>
                        </div>
                    </td>
                    <td data-label="Energy" class="text-md-end"><span class="ops-num">${opsFormatEnergy(reservation.energyKWh)}</span></td>
                    <td data-label="Status">${opsStatusBadge(reservation.status)}</td>
                </tr>`;
        }).join("");
    }

    renderPreviewFooter("pendingFooter", reservations.length, totalCount, "pending requests");
}

// The API returns at most 10 rows per list, so say when more exist.
function renderPreviewFooter(footerId, shownCount, totalCount, label) {
    const footer = document.getElementById(footerId);

    if (totalCount > shownCount) {
        footer.innerHTML = `
            <span><i class="bi bi-info-circle"></i> Showing the first ${shownCount} of ${totalCount.toLocaleString("en-US")} ${label}.</span>
            <a class="ops-link" href="reservations.html">View all reservations <i class="bi bi-arrow-right"></i></a>`;
        footer.hidden = false;
    } else {
        footer.hidden = true;
    }
}

function renderLoadError(error) {
    const isSessionExpired = error.status === 401;

    if (error.status === 403) {
        opsRenderRestricted("Your account does not have permission to view the operator dashboard.");
        return;
    }

    const action = isSessionExpired
        ? `<button type="button" class="btn btn-sm btn-primary" id="alertActionBtn"><i class="bi bi-box-arrow-in-right"></i> Sign in again</button>`
        : `<button type="button" class="btn btn-sm btn-outline-secondary" id="alertActionBtn"><i class="bi bi-arrow-clockwise"></i> Try again</button>`;

    document.getElementById("dashboardAlert").innerHTML = `
        <div class="ops-banner ops-banner--danger ops-animate" role="alert">
            <i class="bi bi-exclamation-octagon-fill" aria-hidden="true"></i>
            <div class="ops-banner-body">
                <p class="ops-banner-title">${isSessionExpired ? "Session expired" : "Couldn't load the dashboard"}</p>
                <p class="ops-banner-text">${opsEscape(error.message)}</p>
            </div>
            ${action}
        </div>`;

    document.getElementById("alertActionBtn").addEventListener("click", () => {
        if (isSessionExpired) {
            opsLogout();
        } else {
            loadDashboard({ keepContent: hasLoadedOnce });
        }
    });

    // Keep previously loaded figures on screen; only replace placeholders.
    if (hasLoadedOnce) {
        document.getElementById("dashboardMeta").classList.add("is-stale");
        return;
    }

    KPI_FIELDS.forEach(({ elementId }) => {
        document.getElementById(elementId).textContent = "—";
    });

    const unavailable = fullWidthRow(opsEmptyState({
        tone: "danger",
        icon: "bi-cloud-slash",
        title: "Data unavailable",
        text: "Bookings could not be loaded. Use Try again above once the connection is restored."
    }));

    document.getElementById("todayBody").innerHTML = unavailable;
    document.getElementById("pendingBody").innerHTML = unavailable;
    document.getElementById("lastUpdated").textContent = "Not updated";
}

function renderTableSkeletons() {
    const row = `
        <tr class="ops-skeleton-row" aria-hidden="true">
            <td class="ops-td-primary">
                <div class="ops-person">
                    <span class="ops-skeleton ops-skeleton--circle"></span>
                    <div class="flex-grow-1"><span class="ops-skeleton" style="width: 70%"></span></div>
                </div>
            </td>
            <td><span class="ops-skeleton" style="width: 80%"></span></td>
            <td><span class="ops-skeleton" style="width: 70%"></span></td>
            <td><span class="ops-skeleton" style="width: 50%"></span></td>
            <td><span class="ops-skeleton" style="width: 60%"></span></td>
        </tr>`;

    const skeleton = row.repeat(SKELETON_ROWS);

    document.getElementById("todayBody").innerHTML = skeleton;
    document.getElementById("pendingBody").innerHTML = skeleton;

    KPI_FIELDS.forEach(({ elementId }) => {
        const element = document.getElementById(elementId);
        element.innerHTML = `<span class="ops-skeleton"></span>`;
        delete element.dataset.value;
    });
}

function fullWidthRow(content) {
    return `<tr><td class="ops-td-full p-0" colspan="${TABLE_COLUMNS}">${content}</td></tr>`;
}

// Shows the prosumer's name and NIC. The API returns the NIC as the name when no user is found.
function personCell(booking) {
    const nic = booking.nic || "";
    const name = booking.prosumerName && booking.prosumerName !== nic ? booking.prosumerName : "";
    const displayName = name || nic || "Unknown prosumer";

    return `
        <div class="ops-person">
            <span class="ops-person-avatar" aria-hidden="true">${opsEscape(opsInitials(name || "?"))}</span>
            <div class="ops-min-0">
                <div class="ops-person-name">${opsEscape(displayName)}</div>
                ${name && nic ? `<div class="ops-person-sub">NIC ${opsEscape(nic)}</div>` : ""}
            </div>
        </div>`;
}

function stationName(booking) {
    return booking.stationName || stationNameById.get(booking.nodeId) || "Unknown station";
}
