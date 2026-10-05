// File: UpdateProsumerDto.cs
// Component: User & Access Management
// Description: Data used by a prosumer to update their own profile.

namespace SolarMicrogrid.Api.DTOs;

public class UpdateProsumerDto
{
    public string FullName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;
}