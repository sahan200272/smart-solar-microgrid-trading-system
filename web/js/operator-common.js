// File:        operator-common.js
// Component:   Booking Views & Grid Operator Verification
// Description: Shared helpers for the Grid Operator web pages: session handling,
//              API requests, date/energy formatting and UI feedback (toasts, confirm dialog).
// Author:      Gunathilaka K.K.N.M.

// Times are shown in Sri Lankan time, matching how the API defines "today".
const OPS_TIME_ZONE = "Asia/Colombo";

// Remembers the operator's selected node across the dashboard and verification pages.
const OPS_NODE_STORAGE_KEY = "operatorNodeId";

const opsDateFormat = new Intl.DateTimeFormat("en-GB", {
    timeZone: OPS_TIME_ZONE, day: "numeric", month: "short", year: "numeric"
});
const opsShortDateFormat = new Intl.DateTimeFormat("en-GB", {
    timeZone: OPS_TIME_ZONE, day: "numeric", month: "short"
});
const opsTimeFormat = new Intl.DateTimeFormat("en-US", {
    timeZone: OPS_TIME_ZONE, hour: "numeric", minute: "2-digit"
});
// Produces yyyy-mm-dd, used to check whether two times fall on the same day.
const opsDayKeyFormat = new Intl.DateTimeFormat("en-CA", { timeZone: OPS_TIME_ZONE });

const opsPrefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

let opsSession = null;

// Error thrown by opsApi, carrying the HTTP status and the API's failure reason code.
class OpsApiError extends Error {
    constructor(status, message, reason, data) {
        super(message);
        this.status = status;
        this.reason = reason || null;
        this.data = data || {};
    }
}

// ---------- Session and navigation ----------

// Reads the logged-in user saved by the login page. Redirects to login when missing.
function opsGetSession() {
    let token = null;
    let user = null;

    try {
        token = localStorage.getItem("token");
        user = JSON.parse(localStorage.getItem("user") || "null");
    } catch (e) {
        user = null;
    }

    if (!token || !user) {
        window.location.href = "login.html";
        return null;
    }

    return { token, user };
}

function opsLogout() {
    localStorage.removeItem("token");
    localStorage.removeItem("user");

    window.location.href = "login.html";
}

// Only Grid Operators and Backoffice users can use the operator console.
function opsCanViewConsole(role) {
    return role === "GridOperator" || role === "Backoffice";
}

function opsRoleLabel(role) {
    return role === "GridOperator" ? "Grid Operator" : (role || "");
}

// Fills the navbar user details, wires sign-out and removes links the role cannot use.
// Returns the session, or null when the user is being redirected to login.
function opsInitPage() {
    opsSession = opsGetSession();

    if (!opsSession) {
        return null;
    }

    const { user } = opsSession;
    const displayName = user.fullName || user.nic || "User";

    document.getElementById("opsUserName").textContent = displayName;
    document.getElementById("opsUserRole").textContent = opsRoleLabel(user.role);
    document.getElementById("opsAvatar").textContent = opsInitials(displayName);
    document.getElementById("opsLogoutBtn").addEventListener("click", opsLogout);

    document.querySelectorAll("[data-ops-role]").forEach(element => {
        const allowedRoles = element.dataset.opsRole.split(",");

        if (!allowedRoles.includes(user.role)) {
            element.remove();
        }
    });

    return opsSession;
}

// Replaces the page content with an "access restricted" message.
function opsRenderRestricted(message) {
    document.getElementById("opsMain").innerHTML = `
        <div class="ops-card ops-animate" style="max-width: 560px; margin: 3rem auto 0;">
            ${opsEmptyState({
                tone: "locked",
                icon: "bi-shield-lock",
                title: "Access restricted",
                text: message,
                action: `<a href="dashboard.html" class="btn btn-primary"><i class="bi bi-arrow-left"></i> Back to dashboard</a>`
            })}
        </div>`;
}

// ---------- API ----------

// Calls the API with the saved JWT and returns the parsed JSON body.
// Throws OpsApiError with a readable message when the request fails.
async function opsApi(path, { method = "GET", body } = {}) {
    let response;

    try {
        response = await fetch(`${API_BASE_URL}${path}`, {
            method,
            headers: {
                "Content-Type": "application/json",
                "Authorization": `Bearer ${opsSession ? opsSession.token : ""}`
            },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
    } catch (networkError) {
        throw new OpsApiError(0, "Unable to connect to the API. Check that the server is running and try again.");
    }

    // Some responses (e.g. 401/403 from the auth middleware) have no body
    const text = await response.text();
    let data = {};

    if (text) {
        try {
            data = JSON.parse(text);
        } catch (e) {
            data = {};
        }
    }

    if (!response.ok) {
        const message = data.message || data.title || opsDefaultErrorMessage(response.status);
        throw new OpsApiError(response.status, message, data.reason, data);
    }

    return data;
}

function opsDefaultErrorMessage(status) {
    switch (status) {
        case 401: return "Your session has expired. Please sign in again.";
        case 403: return "Your account does not have permission for this action.";
        case 404: return "The requested item could not be found.";
        default:
            return status >= 500
                ? "The server ran into a problem. Please try again shortly."
                : `Request failed (HTTP ${status}).`;
    }
}

// Loads active stations into a <select>, restoring the saved node when it still exists.
// Returns the stations, or an empty list if they could not be loaded.
async function opsLoadStations(select) {
    let stations = [];

    try {
        stations = await opsApi("/operator/stations");
    } catch (error) {
        console.warn("Unable to load stations:", error);
        return [];
    }

    const savedNodeId = opsGetSavedNodeId();

    stations
        .slice()
        .sort((a, b) => (a.stationName || "").localeCompare(b.stationName || ""))
        .forEach(station => {
            const option = document.createElement("option");
            option.value = station.id;
            option.textContent = station.stationName || station.id;
            select.appendChild(option);
        });

    if (savedNodeId && stations.some(station => station.id === savedNodeId)) {
        select.value = savedNodeId;
    }

    return stations;
}

// localStorage can be unavailable (private mode, blocked storage), so failures are ignored.
function opsGetSavedNodeId() {
    try {
        return localStorage.getItem(OPS_NODE_STORAGE_KEY) || "";
    } catch (e) {
        return "";
    }
}

function opsSaveNodeId(nodeId) {
    try {
        if (nodeId) {
            localStorage.setItem(OPS_NODE_STORAGE_KEY, nodeId);
        } else {
            localStorage.removeItem(OPS_NODE_STORAGE_KEY);
        }
    } catch (e) {
        // Not critical: the selection just won't be remembered.
    }
}

// ---------- Formatting ----------

function opsEscape(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

// Parses an API timestamp. Values without an offset are treated as UTC, as stored by the API.
function opsParseDate(value) {
    if (!value) {
        return null;
    }

    const text = typeof value === "string" && !/(Z|[+-]\d{2}:?\d{2})$/i.test(value)
        ? `${value}Z`
        : value;
    const date = new Date(text);

    return isNaN(date.getTime()) ? null : date;
}

function opsFormatDate(value) {
    const date = opsParseDate(value);
    return date ? opsDateFormat.format(date) : "—";
}

function opsFormatTime(value) {
    const date = opsParseDate(value);
    return date ? opsTimeFormat.format(date) : "—";
}

function opsFormatDateTime(value) {
    const date = opsParseDate(value);
    return date ? `${opsDateFormat.format(date)}, ${opsTimeFormat.format(date)}` : "—";
}

// Returns { date, time } text for a booking slot, e.g. "26 Sep 2026" and "9:00 AM – 10:00 AM".
function opsFormatSlot(startValue, endValue) {
    const start = opsParseDate(startValue);
    const end = opsParseDate(endValue);

    if (!start) {
        return { date: "Not scheduled", time: "—" };
    }

    const date = opsDateFormat.format(start);

    if (!end) {
        return { date, time: `From ${opsTimeFormat.format(start)}` };
    }

    const sameDay = opsDayKeyFormat.format(start) === opsDayKeyFormat.format(end);
    const endText = sameDay
        ? opsTimeFormat.format(end)
        : `${opsShortDateFormat.format(end)}, ${opsTimeFormat.format(end)}`;

    return { date, time: `${opsTimeFormat.format(start)} – ${endText}` };
}

// Describes where "now" sits relative to a slot: upcoming, in progress or ended.
function opsSlotPhase(startValue, endValue) {
    const start = opsParseDate(startValue);
    const end = opsParseDate(endValue);
    const now = Date.now();

    if (!start) {
        return null;
    }

    if (now < start.getTime()) {
        const minutes = Math.round((start.getTime() - now) / 60000);
        const label = minutes < 60
            ? `Starts in ${Math.max(minutes, 1)} min`
            : `Starts in ${Math.floor(minutes / 60)} h ${minutes % 60} min`;
        return { key: "upcoming", label };
    }

    if (!end || now < end.getTime()) {
        return { key: "live", label: "In progress" };
    }

    return { key: "ended", label: "Slot ended" };
}

function opsPhaseBadge(phase) {
    if (!phase) {
        return "";
    }

    const tone = { upcoming: "neutral", live: "approved ops-badge--live", ended: "cancelled" }[phase.key];
    return `<span class="ops-badge ops-badge--${tone}">${opsEscape(phase.label)}</span>`;
}

function opsFormatNumber(value) {
    return Number(value).toLocaleString("en-US", { maximumFractionDigits: 2 });
}

// Energy values of 0 come from older bookings that did not record an amount.
function opsFormatEnergy(value) {
    const amount = Number(value);
    return Number.isFinite(amount) && amount > 0 ? `${opsFormatNumber(amount)} kWh` : "—";
}

function opsInitials(name) {
    const parts = String(name || "").trim().split(/\s+/).filter(Boolean);

    if (parts.length === 0) {
        return "?";
    }

    const letters = parts.length === 1
        ? parts[0].slice(0, 2)
        : parts[0][0] + parts[parts.length - 1][0];

    return letters.toUpperCase();
}

function opsStatusBadge(status) {
    const key = String(status || "").toLowerCase();
    const knownStatuses = ["pending", "approved", "completed", "cancelled"];
    const tone = knownStatuses.includes(key) ? key : "neutral";

    return `<span class="ops-badge ops-badge--${tone}">${opsEscape(status || "Unknown")}</span>`;
}

// ---------- UI feedback ----------

function opsEmptyState({ icon, title, text, tone = "", action = "" }) {
    return `
        <div class="ops-empty ${tone ? `ops-empty--${tone}` : ""}">
            <span class="ops-empty-icon"><i class="bi ${icon}"></i></span>
            <p class="ops-empty-title">${opsEscape(title)}</p>
            <p class="ops-empty-text">${opsEscape(text)}</p>
            ${action}
        </div>`;
}

// Shows a spinner on a button while an action runs, and restores it afterwards.
function opsSetBusy(button, busy, busyLabel = "Working…") {
    if (!button) {
        return;
    }

    if (busy) {
        button.dataset.idleHtml = button.innerHTML;
        button.disabled = true;
        button.innerHTML = `<span class="spinner-border spinner-border-sm" aria-hidden="true"></span><span>${opsEscape(busyLabel)}</span>`;
    } else {
        button.disabled = false;

        if (button.dataset.idleHtml) {
            button.innerHTML = button.dataset.idleHtml;
            delete button.dataset.idleHtml;
        }
    }
}

// Shows a short notification in the top-right corner.
function opsToast(message, variant = "success") {
    let container = document.getElementById("opsToasts");

    if (!container) {
        container = document.createElement("div");
        container.id = "opsToasts";
        container.className = "ops-toasts";
        container.setAttribute("aria-live", "polite");
        document.body.appendChild(container);
    }

    const icons = {
        success: "bi-check-circle-fill",
        danger: "bi-x-circle-fill",
        warning: "bi-exclamation-triangle-fill",
        info: "bi-info-circle-fill"
    };

    const toast = document.createElement("div");
    toast.className = `ops-toast ops-toast--${variant}`;
    toast.setAttribute("role", variant === "danger" ? "alert" : "status");
    toast.innerHTML = `
        <i class="bi ${icons[variant] || icons.info}" aria-hidden="true"></i>
        <div class="ops-toast-body">${opsEscape(message)}</div>
        <button type="button" class="btn-close" aria-label="Dismiss"></button>`;

    const dismiss = () => {
        if (toast.classList.contains("is-leaving")) {
            return;
        }

        toast.classList.add("is-leaving");
        toast.addEventListener("animationend", () => toast.remove(), { once: true });
        setTimeout(() => toast.remove(), 400);
    };

    toast.querySelector(".btn-close").addEventListener("click", dismiss);
    setTimeout(dismiss, 5000);

    container.appendChild(toast);
}

// Shows a styled confirmation dialog. Resolves true when the user confirms.
// "message" is HTML, so callers must escape any values they insert.
function opsConfirm({ title, message, confirmLabel = "Confirm", variant = "primary", icon = "bi-question-lg" }) {
    let modalElement = document.getElementById("opsConfirmModal");

    if (!modalElement) {
        modalElement = document.createElement("div");
        modalElement.id = "opsConfirmModal";
        modalElement.className = "modal fade ops-modal";
        modalElement.tabIndex = -1;
        modalElement.setAttribute("aria-labelledby", "opsConfirmTitle");
        modalElement.setAttribute("aria-hidden", "true");
        modalElement.innerHTML = `
            <div class="modal-dialog modal-dialog-centered" style="max-width: 440px;">
                <div class="modal-content">
                    <div class="modal-body">
                        <span class="ops-modal-icon" aria-hidden="true"></span>
                        <h2 class="ops-modal-title" id="opsConfirmTitle"></h2>
                        <div class="ops-modal-text"></div>
                    </div>
                    <div class="modal-footer">
                        <button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>
                        <button type="button" class="btn" data-ops-confirm></button>
                    </div>
                </div>
            </div>`;
        document.body.appendChild(modalElement);
    }

    const iconElement = modalElement.querySelector(".ops-modal-icon");
    iconElement.className = `ops-modal-icon ops-modal-icon--${variant}`;
    iconElement.innerHTML = `<i class="bi ${icon}"></i>`;

    modalElement.querySelector(".ops-modal-title").textContent = title;
    modalElement.querySelector(".ops-modal-text").innerHTML = message;

    const confirmButton = modalElement.querySelector("[data-ops-confirm]");
    confirmButton.className = `btn btn-${variant}`;
    confirmButton.textContent = confirmLabel;

    const modal = bootstrap.Modal.getOrCreateInstance(modalElement);

    return new Promise(resolve => {
        let confirmed = false;

        const onConfirm = () => {
            confirmed = true;
            modal.hide();
        };

        confirmButton.addEventListener("click", onConfirm);
        modalElement.addEventListener("shown.bs.modal", () => confirmButton.focus(), { once: true });
        modalElement.addEventListener("hidden.bs.modal", () => {
            confirmButton.removeEventListener("click", onConfirm);
            resolve(confirmed);
        }, { once: true });

        modal.show();
    });
}

// Animates a number from its previous value to the new one.
function opsAnimateCount(element, target) {
    const to = Number(target) || 0;
    const from = Number(element.dataset.value) || 0;

    element.dataset.value = String(to);

    if (opsPrefersReducedMotion || from === to) {
        element.textContent = to.toLocaleString("en-US");
        return;
    }

    const duration = 650;
    const startedAt = performance.now();

    const step = now => {
        const progress = Math.min(1, (now - startedAt) / duration);
        const eased = 1 - Math.pow(1 - progress, 3);

        element.textContent = Math.round(from + (to - from) * eased).toLocaleString("en-US");

        if (progress < 1) {
            requestAnimationFrame(step);
        }
    };

    requestAnimationFrame(step);
}
