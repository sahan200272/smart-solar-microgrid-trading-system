using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace SolarMicrogrid.Api.Models;

// Represents a single Microgrid Node (solar charging/trading hub)
// Stored in the "SolarStationInfo" MongoDB collection
public class SolarStationInfo
{
    // MongoDB auto-generates this unique ID for every document
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string? Id { get; set; }

    // Human-readable name for the hub, e.g. "Negombo Beach Hub"
    [BsonElement("stationName")]
    public string StationName { get; set; } = string.Empty;

    // GPS coordinates - needed for the "nearby nodes" map feature
    [BsonElement("latitude")]
    public double Latitude { get; set; }

    [BsonElement("longitude")]
    public double Longitude { get; set; }

    // Maximum power capacity this hub can handle, in kW/h
    [BsonElement("capacityKWh")]
    public double CapacityKWh { get; set; }

    // Total number of battery storage slots available at this hub
    [BsonElement("totalBatterySlots")]
    public int TotalBatterySlots { get; set; }

    // How many slots are currently free (updated as bookings happen)
    [BsonElement("availableBatterySlots")]
    public int AvailableBatterySlots { get; set; }

    // Operating schedule, e.g. "06:00 - 22:00"
    [BsonElement("operatingSchedule")]
    public string OperatingSchedule { get; set; } = string.Empty;

    // Whether this hub is currently active or deactivated
    [BsonElement("isActive")]
    public bool IsActive { get; set; } = true;

    // Timestamp for when this hub was created (good practice for auditing)
    [BsonElement("createdAt")]
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}