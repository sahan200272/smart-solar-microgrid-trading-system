// File: LoginDto.cs
// Component:   User & Access Management
// Description: Receives login credentials from clients.

using System.ComponentModel.DataAnnotations;

namespace SolarMicrogrid.Api.DTOs;

public class LoginDto
{
    [Required(ErrorMessage = "NIC is required.")]
    [StringLength(12, MinimumLength = 10, ErrorMessage = "NIC must be between 10 and 12 characters.")]
    [RegularExpression(@"^(\d{9}[vVxX]|\d{12})$", ErrorMessage = "Invalid NIC format. Must be 9 digits followed by V/X or 12 digits.")]
    public string NIC { get; set; } = string.Empty;

    [Required(ErrorMessage = "Password is required.")]
    [StringLength(100, MinimumLength = 6, ErrorMessage = "Password must be at least 6 characters long.")]
    public string Password { get; set; } = string.Empty;
}