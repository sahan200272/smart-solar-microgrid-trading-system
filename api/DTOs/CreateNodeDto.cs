namespace SolarMicrogrid.Api.DTOs;

// What the CLIENT sends when creating a new microgrid node.
// Only includes fields the client should control - server fills in the rest.
public class CreateNodeDto
{
    public string StationName { get; set; } = string.Empty;
    public double Latitude { get; set; }
    public double Longitude { get; set; }
    public double CapacityKWh { get; set; }
    public int TotalBatterySlots { get; set; }
    public string OperatingSchedule { get; set; } = string.Empty;
}