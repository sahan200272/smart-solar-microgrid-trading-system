// File:        VerifyQrDto.cs
// Component:   Booking Views & Grid Operator Verification
// Description: Request data sent by a Grid Operator after scanning a QR code.
// Author:      Gunathilaka K.K.N.M.

namespace SolarMicrogrid.Api.DTOs;

public class VerifyQrDto
{
    // Scanned QR value, either the raw token or the full QR payload.
    public string? QrToken { get; set; }

    // Optional id of the node where the operator is working.
    public string? NodeId { get; set; }
}
