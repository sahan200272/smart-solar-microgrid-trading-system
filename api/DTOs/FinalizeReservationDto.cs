// File:        FinalizeReservationDto.cs
// Component:   Booking Views & Grid Operator Verification
// Description: Request data sent by a Grid Operator to finalise an energy transfer.
// Author:      Gunathilaka K.K.N.M.

namespace SolarMicrogrid.Api.DTOs;

public class FinalizeReservationDto
{
    // QR token scanned from the prosumer's QR code.
    public string QrToken { get; set; } = string.Empty;

    // Node id where the operator is verifying/finalizing.
    public string? NodeId { get; set; }

    // Energy actually delivered in kWh (optional).
    public double? DeliveredKWh { get; set; }
}
