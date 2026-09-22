// File: UpdateUserDto.cs
// Component: User & Access Management
// Description: Data used by Backofice to update user information and activate /deactivate accounts.

namespace SolarMicrogrid.Api.DTOs;

public class UpdateUserDto
{
    public string FullName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;

// Active, Pending or Deactivated.
    public string Status { get; set; } = string.Empty;
}