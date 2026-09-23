document.addEventListener("DOMContentLoaded", () => {
    document
        .getElementById("loginForm")
        .addEventListener("submit", handleLogin);
});

async function handleLogin(event) {
    event.preventDefault();

    const nic = document.getElementById("nic").value.trim();
    const password = document.getElementById("password").value;

    const errorMessage = document.getElementById("errorMessage");

    errorMessage.classList.add("d-none");

    try {
        const response = await fetch(`${API_BASE_URL}/auth/login`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                NIC: nic,
                Password: password
            })
        });


        const data = await response.json();
        if (!response.ok) {
            errorMessage.textContent =
                data.message || "Login failed.";
            errorMessage.classList.remove("d-none");
            return;
        }


        // Save JWT token
        localStorage.setItem("token", data.token);

        // Save logged-in user information
        localStorage.setItem(
            "user",
            JSON.stringify(data.user)
        );


        // Go to dashboard
        window.location.href = "dashboard.html";

    } catch (error) {

        console.error("Login error:", error);

        errorMessage.textContent =
            "Unable to connect to the API.";

        errorMessage.classList.remove("d-none");
    }
}