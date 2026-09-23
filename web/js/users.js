document.addEventListener("DOMContentLoaded", () => {
    checkLogin();
    loadUsers();

    document
        .getElementById("refreshBtn")
        .addEventListener("click", loadUsers);

    document
        .getElementById("createUserBtn")
        .addEventListener("click", handleCreateUser);

    document
        .getElementById("saveEditBtn")
        .addEventListener("click", handleSaveEdit);
});


function checkLogin() {

    const token = localStorage.getItem("token");
    const userData = localStorage.getItem("user");

    if (!token || !userData) {
        window.location.href = "login.html";
        return;
    }

    const user = JSON.parse(userData);

    // Only Backoffice can access UsersController
    if (user.role !== "Backoffice") {
        alert("Access denied.");
        window.location.href = "dashboard.html";
    }
}


function getAuthHeaders() {
    return {
        "Content-Type": "application/json",
        "Authorization":
            `Bearer ${localStorage.getItem("token")}`
    };
}


async function loadUsers() {

    try {
        const response = await fetch(
            `${API_BASE_URL}/users`,
            {
                headers: getAuthHeaders()
            }
        );


        if (!response.ok) {
            throw new Error(
                `GET /users failed: ${response.status}`
            );
        }

        const users = await response.json();

        renderUsersTable(users);


    } catch (error) {
        console.error(
            "Failed to load users:",
            error
        );
        alert(
            "Could not load users. " +
            error.message
        );
    }
}


function renderUsersTable(users) {

    const tableBody =
        document.getElementById("usersTableBody");

    tableBody.innerHTML = "";


    users.forEach(user => {

        let statusBadge;

        if (user.status === "Active") {
            statusBadge =
                `<span class="badge bg-success">
                    Active
                 </span>`;
        } else if (user.status === "Pending") {
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


        const row =
            document.createElement("tr");


        row.innerHTML = `
            <td>${user.nic}</td>
            <td>${user.fullName}</td>
            <td>${user.email || "-"}</td>
            <td>${user.role}</td>
            <td>${statusBadge}</td>

            <td>
                <button
                    class="btn btn-sm btn-outline-primary"
                    onclick="openEditModal('${user.id}')">
                    Edit
                </button>

                <button
                    class="btn btn-sm btn-outline-danger"
                    onclick="handleDeactivate('${user.id}')"
                    ${user.status === "Deactivated"
                        ? "disabled"
                        : ""}>
                    Deactivate
                </button>
            </td>
        `;
        tableBody.appendChild(row);
    });
}

function openCreateModal(role) {

    document.getElementById("createRole").value = role;

    document.getElementById("createModalTitle").textContent =
        role === "Backoffice"
            ? "Create Backoffice User"
            : "Create Grid Operator";

    const modal =
        new bootstrap.Modal(
            document.getElementById("createUserModal")
        );

    modal.show();
}

async function handleCreateUser() {

    const role =
        document.getElementById("createRole").value;

    const userData = {
        NIC:
            document.getElementById("createNIC").value,
        FullName:
            document.getElementById("createFullName").value,
        Email:
            document.getElementById("createEmail").value,
        Phone:
            document.getElementById("createPhone").value,
        Password:
            document.getElementById("createPassword").value
    };


    const endpoint =
        role === "Backoffice"
            ? "/users/backoffice"
            : "/users/gridoperator";

    try {
        const response = await fetch(
            `${API_BASE_URL}${endpoint}`,
            {
                method: "POST",
                headers: getAuthHeaders(),
                body: JSON.stringify(userData)
            }
        );

        const data = await response.json();

        if (!response.ok) {
            alert(
                "Error: " +
                (data.message || "Could not create user.")
            );
            return;
        }

        alert(
            data.message ||
            "User created successfully."
        );

        bootstrap.Modal
            .getInstance(
                document.getElementById("createUserModal")
            )
            .hide();

        document.getElementById("createNIC").value = "";
        document.getElementById("createFullName").value = "";
        document.getElementById("createEmail").value = "";
        document.getElementById("createPhone").value = "";
        document.getElementById("createPassword").value = "";

        loadUsers();
    } catch (error) {

        console.error(
            "Failed to create user:",
            error
        );

        alert(
            "Something went wrong creating the user."
        );
    }
}


async function openEditModal(userId) {
    try {
        const response = await fetch(
            `${API_BASE_URL}/users/${userId}`,
            {
                headers: getAuthHeaders()
            }
        );

        if (!response.ok) {
            throw new Error(
                "Could not load user details."
            );
        }

        const user = await response.json();

        document.getElementById("editUserId").value =
            user.id;

        document.getElementById("editFullName").value =
            user.fullName;

        document.getElementById("editEmail").value =
            user.email || "";

        document.getElementById("editPhone").value =
            user.phone || "";

        document.getElementById("editAddress").value =
            user.address || "";

        document.getElementById("editStatus").value =
            user.status;

        const modal =
            new bootstrap.Modal(
                document.getElementById("editUserModal")
            );

        modal.show();

    } catch (error) {

        console.error(
            "Failed to load user:",
            error
        );

        alert(error.message);
    }
}

async function handleSaveEdit() {

    const userId =
        document.getElementById("editUserId").value;

    const updatedUser = {

        FullName:
            document.getElementById("editFullName").value,
        Email:
            document.getElementById("editEmail").value,
        Phone:
            document.getElementById("editPhone").value,
        Address:
            document.getElementById("editAddress").value,
        Status:
            document.getElementById("editStatus").value
    };


    try {
        const response = await fetch(
            `${API_BASE_URL}/users/${userId}`,
            {
                method: "PUT",
                headers: getAuthHeaders(),
                body: JSON.stringify(updatedUser)
            }
        );


        const data = await response.json();

        if (!response.ok) {
            alert(
                "Error: " +
                (data.message || "Update failed.")
            );
            return;
        }

        alert(
            data.message ||
            "User updated successfully."
        );

        bootstrap.Modal
            .getInstance(
                document.getElementById("editUserModal")
            )
            .hide();
        loadUsers();
    } catch (error) {
        console.error(
            "Failed to update user:",
            error
        );
        alert("Something went wrong updating the user.");
    }
}

async function handleDeactivate(userId) {
    if (!confirm(
        "Are you sure you want to deactivate this user?"
    )) {
        return;
    }
    try {
        const response = await fetch(
            `${API_BASE_URL}/users/${userId}`,
            {
                method: "DELETE",
                headers: getAuthHeaders()
            }
        );
        const data = await response.json();
        if (!response.ok) {
            alert(
                "Cannot deactivate: " +
                (data.message || "Operation failed.")
            );
            return;
        }
        alert(
            data.message ||
            "User deactivated successfully."
        );
        loadUsers();
    } catch (error) {
        console.error(
            "Failed to deactivate user:",
            error
        );
        alert(
            "Something went wrong."
        );
    }
}