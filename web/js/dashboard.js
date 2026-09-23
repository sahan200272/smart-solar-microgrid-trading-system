document.addEventListener("DOMContentLoaded", () => {
    const token = localStorage.getItem("token");
    const userData = localStorage.getItem("user");

    // User is not logged in
    if (!token || !userData) {
        window.location.href = "login.html";
        return;
    }

    const user = JSON.parse(userData);

    document.getElementById("userName").textContent =
        user.fullName;

    document.getElementById("userRole").textContent =
        user.role;

    // Only Backoffice can manage users
    if (user.role !== "Backoffice") {
        document
            .getElementById("userManagementCard")
            .style.display = "none";
    }
});

function logout() {

    localStorage.removeItem("token");
    localStorage.removeItem("user");

    window.location.href = "login.html";
}