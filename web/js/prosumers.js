let allProsumers = [];

document.addEventListener("DOMContentLoaded", () => {
    checkAccess();
    loadProsumers();

    document
        .getElementById("refreshBtn")
        .addEventListener("click", loadProsumers);

    document
        .getElementById("searchNIC")
        .addEventListener("input", applyFilters);

    document
        .getElementById("searchName")
        .addEventListener("input", applyFilters);

    document
        .getElementById("statusFilter")
        .addEventListener("change", applyFilters);
});

function getUser() {
    const userData =
        localStorage.getItem("user");

    return userData
        ? JSON.parse(userData)
        : null;
}

function checkAccess() {
    const token =
        localStorage.getItem("token");

    const user =
        getUser();

    if (!token || !user) {
        window.location.href = "login.html";
        return;
    }

    if (
        user.role !== "Backoffice" &&
        user.role !== "GridOperator"
    ) {
        alert("Access denied.");
        window.location.href =
            "dashboard.html";
        return;
    }

    // Grid Operators can view Prosumers but never act on them,
    // so the whole Actions column is hidden for that role.
    if (user.role !== "Backoffice") {
        document
            .getElementById("actionsHeader")
            .style.display = "none";
    }
}

function getAuthHeaders() {
    return {
        "Content-Type":
            "application/json",
        "Authorization":
            `Bearer ${localStorage.getItem("token")}`
    };
}

async function loadProsumers() {
    try {
        const response = await fetch(
            `${API_BASE_URL}/prosumers`,
            {
                headers: getAuthHeaders()
            }
        );

        if (!response.ok) {
            throw new Error(
                `Failed to load prosumers: ${response.status}`
            );
        }

        allProsumers =
            await response.json();

        applyFilters();
    } catch (error) {
        console.error(error);
        alert(
            "Could not load prosumers."
        );
    }
}

function applyFilters() {
    const nicQuery =
        document.getElementById("searchNIC")
            .value.trim().toLowerCase();

    const nameQuery =
        document.getElementById("searchName")
            .value.trim().toLowerCase();

    const statusQuery =
        document.getElementById("statusFilter")
            .value;

    const filtered = allProsumers.filter(prosumer => {
        const matchesNIC =
            !nicQuery ||
            prosumer.nic.toLowerCase().includes(nicQuery);

        const matchesName =
            !nameQuery ||
            prosumer.fullName.toLowerCase().includes(nameQuery);

        const matchesStatus =
            statusQuery === "All" ||
            prosumer.status === statusQuery;

        return matchesNIC && matchesName && matchesStatus;
    });

    renderProsumers(filtered);
}

function renderProsumers(prosumers) {
    const tableBody =
        document.getElementById(
            "prosumersTableBody"
        );
    tableBody.innerHTML = "";

    const user =
        getUser();

    const isBackoffice =
        user.role === "Backoffice";

    if (prosumers.length === 0) {
        const colspan = isBackoffice ? 6 : 5;
        tableBody.innerHTML =
            `<tr><td colspan="${colspan}" class="text-center text-muted">No prosumers match your filters.</td></tr>`;
        return;
    }

    prosumers.forEach(prosumer => {
        let statusBadge;

        if (prosumer.status === "Active") {
            statusBadge =
                `<span class="badge bg-success">
                    Active
                 </span>`;
        } else if (prosumer.status === "Pending") {
            statusBadge =
                `<span class="badge bg-warning text-dark">
                    Pending
                 </span>`;
        } else {
            statusBadge =
                `<span class="badge bg-secondary">
                    Deactivated
                 </span>`;
        }

        let actions = "";

        // Only Backoffice can change status
        if (isBackoffice) {

            if (prosumer.status === "Pending") {

                actions = `
                    <button
                        class="btn btn-sm btn-outline-success"
                        onclick="activateProsumer('${prosumer.nic}')">
                        Activate
                    </button>
                `;

            } else if (prosumer.status === "Deactivated") {

                actions = `
                    <button
                        class="btn btn-sm btn-outline-success"
                        onclick="reactivateProsumer('${prosumer.nic}')">
                        Reactivate
                    </button>
                `;

            } else {

                actions = `
                    <button
                        class="btn btn-sm btn-outline-danger"
                        onclick="deactivateProsumer('${prosumer.nic}')">
                        Deactivate
                    </button>
                `;
            }
        }

        const row =
            document.createElement("tr");

        row.innerHTML = `
            <td>${prosumer.nic}</td>
            <td>${prosumer.fullName}</td>
            <td>${prosumer.email || "-"}</td>
            <td>${prosumer.phone || "-"}</td>
            <td>${statusBadge}</td>
            ${isBackoffice ? `<td>${actions}</td>` : ""}
        `;
        tableBody.appendChild(row);
    });
}

async function deactivateProsumer(nic) {
    if (!confirm(
        "Are you sure you want to deactivate this Prosumer?"
    )) {

        return;
    }

    try {
        const response = await fetch(
            `${API_BASE_URL}/prosumers/${nic}/deactivate`,
            {
                method: "PUT",
                headers: getAuthHeaders()
            }
        );

        const data =
            await response.json();

        if (!response.ok) {
            alert(
                data.message ||
                "Could not deactivate Prosumer."
            );
            return;
        }
        alert(
            data.message ||
            "Prosumer deactivated."
        );
        loadProsumers();
    } catch (error) {
        console.error(error);
        alert(
            "Something went wrong."
        );
    }
}

async function reactivateProsumer(nic) {
    try {
        const response = await fetch(
            `${API_BASE_URL}/prosumers/${nic}/reactivate`,
            {
                method: "PUT",
                headers: getAuthHeaders()
            }
        );
        const data =
            await response.json();

        if (!response.ok) {
            alert(
                data.message ||
                "Could not reactivate Prosumer."
            );
            return;
        }
        alert(
            data.message ||
            "Prosumer reactivated."
        );
        loadProsumers();
    } catch (error) {
        console.error(error);

        alert(
            "Something went wrong."
        );
    }
}

async function activateProsumer(nic) {
    try {
        const response = await fetch(
            `${API_BASE_URL}/prosumers/${nic}/activate`,
            {
                method: "PUT",
                headers: getAuthHeaders()
            }
        );

        const data = await response.json();

        if (!response.ok) {
            alert(
                data.message ||
                "Could not activate Prosumer."
            );
            return;
        }

        alert(
            data.message ||
            "Prosumer activated successfully."
        );

        loadProsumers();

    } catch (error) {
        console.error(error);

        alert(
            "Something went wrong."
        );
    }
}