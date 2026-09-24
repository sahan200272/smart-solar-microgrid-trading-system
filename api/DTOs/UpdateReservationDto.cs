namespace SolarMicrogrid.Api.DTOs;

public class UpdateReservationDto
{
    public DateTime? SlotStartTime { get; set; }
    public DateTime? SlotEndTime { get; set; }
}
