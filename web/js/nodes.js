// Runs once the page has fully loaded
document.addEventListener("DOMContentLoaded", () => {
    loadNodes();

    document.getElementById("createNodeForm").addEventListener("submit", handleCreateNode);
    document.getElementById("refreshBtn").addEventListener("click", loadNodes);
    document.getElementById("saveEditBtn").addEventListener("click", handleSaveEdit);
});

// Fetches all nodes from the API and renders them into the table
async function loadNodes() {
    try {
        const response = await fetch(`${API_BASE_URL}/nodes`);

        if (!response.ok) {
            throw new Error(`GET ${API_BASE_URL}/nodes failed with HTTP ${response.status}`);
        }

        const nodes = await response.json();
        console.log(nodes);
        
        renderNodesTable(nodes);
    } catch (error) {
        console.error("Failed to load nodes:", error);
        alert(`Could not load nodes: ${error.message}`);
    }
}

// Builds the HTML table rows from the node data
function renderNodesTable(nodes) {
    const tableBody = document.getElementById("nodesTableBody");
    tableBody.innerHTML = "";

    nodes.forEach(node => {
        const statusBadge = node.isActive
            ? `<span class="badge bg-success">Active</span>`
            : `<span class="badge bg-secondary">Deactivated</span>`;

        const row = document.createElement("tr");
        row.innerHTML = `
            <td>${node.stationName}</td>
            <td>${node.capacityKWh}</td>
            <td>${node.availableBatterySlots} / ${node.totalBatterySlots}</td>
            <td>${node.operatingSchedule}</td>
            <td>${statusBadge}</td>
            <td>
                <button class="btn btn-sm btn-outline-primary" onclick="openEditModal('${node.id}')">Edit</button>
                <button class="btn btn-sm btn-outline-danger" onclick="handleDeactivate('${node.id}')"
                    ${!node.isActive ? "disabled" : ""}>Deactivate</button>
            </td>
        `;
        tableBody.appendChild(row);
    });
}

// Handles the "Create New Node" form submission
async function handleCreateNode(event) {
    event.preventDefault(); // stop the page from refreshing (default form behavior)

    const newNode = {
        stationName: document.getElementById("stationName").value,
        latitude: parseFloat(document.getElementById("latitude").value),
        longitude: parseFloat(document.getElementById("longitude").value),
        capacityKWh: parseFloat(document.getElementById("capacityKWh").value),
        totalBatterySlots: parseInt(document.getElementById("totalBatterySlots").value),
        operatingSchedule: document.getElementById("operatingSchedule").value
    };

    try {
        const response = await fetch(`${API_BASE_URL}/nodes`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(newNode)
        });

        if (!response.ok) {
            const errorData = await response.json();
            alert("Error: " + errorData.message);
            return;
        }

        document.getElementById("createNodeForm").reset();
        loadNodes(); // refresh the table with the new node included
    } catch (error) {
        console.error("Failed to create node:", error);
        alert("Something went wrong creating the node.");
    }
}

// Opens the edit modal and pre-fills it with the selected node's current data
async function openEditModal(nodeId) {
    try {
        const response = await fetch(`${API_BASE_URL}/nodes/${nodeId}`);
        const node = await response.json();

        document.getElementById("editNodeId").value = node.id;
        document.getElementById("editStationName").value = node.stationName;
        document.getElementById("editLatitude").value = node.latitude;
        document.getElementById("editLongitude").value = node.longitude;
        document.getElementById("editCapacityKWh").value = node.capacityKWh;
        document.getElementById("editTotalBatterySlots").value = node.totalBatterySlots;
        document.getElementById("editOperatingSchedule").value = node.operatingSchedule;

        const modal = new bootstrap.Modal(document.getElementById("editModal"));
        modal.show();
    } catch (error) {
        console.error("Failed to load node details:", error);
    }
}

// Handles saving changes from the edit modal
async function handleSaveEdit() {
    const nodeId = document.getElementById("editNodeId").value;

    const updatedNode = {
        stationName: document.getElementById("editStationName").value,
        latitude: parseFloat(document.getElementById("editLatitude").value),
        longitude: parseFloat(document.getElementById("editLongitude").value),
        capacityKWh: parseFloat(document.getElementById("editCapacityKWh").value),
        totalBatterySlots: parseInt(document.getElementById("editTotalBatterySlots").value),
        operatingSchedule: document.getElementById("editOperatingSchedule").value
    };

    try {
        const response = await fetch(`${API_BASE_URL}/nodes/${nodeId}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(updatedNode)
        });

        if (!response.ok) {
            const errorData = await response.json();
            alert("Error: " + errorData.message);
            return;
        }

        bootstrap.Modal.getInstance(document.getElementById("editModal")).hide();
        loadNodes();
    } catch (error) {
        console.error("Failed to update node:", error);
    }
}

// Handles the "Deactivate" button click
async function handleDeactivate(nodeId) {
    if (!confirm("Are you sure you want to deactivate this node?")) {
        return;
    }

    try {
        const response = await fetch(`${API_BASE_URL}/nodes/${nodeId}/deactivate`, {
            method: "PUT"
        });

        if (!response.ok) {
            const errorData = await response.json();
            alert("Cannot deactivate: " + errorData.message);
            return;
        }

        loadNodes();
    } catch (error) {
        console.error("Failed to deactivate node:", error);
    }
}