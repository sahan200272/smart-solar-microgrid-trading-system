// File: LoginDto.cs
// Component:   User & Access Management
// Description: Receives login credentials from clients.

namespace SolarMicrogrid.Api.DTOs;

public class LoginDto
{
    public string NIC { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
}