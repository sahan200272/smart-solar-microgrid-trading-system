namespace SolarMicrogrid.Api.DTOs;

// What the CLIENT sends when updating an existing microgrid node.
// Notice: no Id, no AvailableBatterySlots, no IsActive here -
// those are managed separately (IsActive has its own deactivate endpoint in Task 5).
public class UpdateNodeDto
{
    public string StationName { get; set; } = string.Empty;
    public double Latitude { get; set; }
    public double Longitude { get; set; }
    public double CapacityKWh { get; set; }
    public int TotalBatterySlots { get; set; }
    public string OperatingSchedule { get; set; } = string.Empty;
}