using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace SolarMicrogrid.Api.Models;

// Represents a time-window slot availability for a specific microgrid node
// Stored in the "EnergyBookingSlots" MongoDB collection
[BsonIgnoreExtraElements]
public class EnergyBookingSlot
{
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string? Id { get; set; }

    // References SolarStationInfo.Id
    [BsonElement("nodeId")]
    public string NodeId { get; set; } = string.Empty;

    [BsonElement("stationName")]
    public string StationName { get; set; } = string.Empty;

    // Time window matching EnergyReservation time field conventions
    [BsonElement("slotStartTime")]
    public DateTime SlotStartTime { get; set; }

    [BsonElement("slotEndTime")]
    public DateTime SlotEndTime { get; set; }

    // Total capacity specs for this slot window
    [BsonElement("totalCapacityKWh")]
    public double TotalCapacityKWh { get; set; }

    // Remaining capacity available to be booked
    [BsonElement("availableCapacityKWh")]
    public double AvailableCapacityKWh { get; set; }

    // Total battery storage slots available in this window
    [BsonElement("totalSlotCount")]
    public int TotalSlotCount { get; set; } = 1;

    // Remaining battery storage slots available
    [BsonElement("availableSlotCount")]
    public int AvailableSlotCount { get; set; } = 1;

    // Status: "Open", "Full", "Closed"
    [BsonElement("status")]
    public string Status { get; set; } = "Open";

    // List of EnergyReservation IDs holding capacity in this slot
    [BsonElement("reservationIds")]
    public List<string> ReservationIds { get; set; } = new();

    [BsonElement("createdAt")]
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    [BsonElement("updatedAt")]
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
}
