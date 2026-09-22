// File: CreateBackofficeUserDto.cs
// Component: User & Access Management
// Description: Data required to create a new backoffice user.

namespace SolarMicrogrid.Api.DTOs;

public class CreateBackofficeUserDto
{
    public string NIC { get; set; } = string.Empty;
    public string FullName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
}