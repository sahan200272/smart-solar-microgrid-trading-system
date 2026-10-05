// File: RegisterProsumerDto.cs
// Component: User & Access Management
// Description: Data submitted when a new prosumer creates an account.

namespace SolarMicrogrid.Api.DTOs;

public class RegisterProsumerDto
{
    public string NIC { get; set; } = string.Empty;
    public string FullName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
}