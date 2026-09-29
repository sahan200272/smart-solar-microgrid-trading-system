namespace SolarMicrogrid.Api.DTOs;

public class UpdateReservationDto
{
    public string? NodeId { get; set; }
    public DateTime? SlotStartTime { get; set; }
    public DateTime? SlotEndTime { get; set; }
    public string? SlotId { get; set; }
}
