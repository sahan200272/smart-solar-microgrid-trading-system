using System.ComponentModel.DataAnnotations;

namespace SolarMicrogrid.Api.DTOs;

public class CreateReservationDto
{
    [Required]
    public string ProsumerNic { get; set; } = string.Empty;

    [Required]
    public string NodeId { get; set; } = string.Empty;

    public string? StationName { get; set; }

    public double EnergyKWh { get; set; }

    [Required]
    public DateTime SlotStartTime { get; set; }

    [Required]
    public DateTime SlotEndTime { get; set; }
}
