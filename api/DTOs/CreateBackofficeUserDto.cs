// File: CreateBackofficeUserDto.cs
// Component: User & Access Management
// Description: Data required to create a new backoffice user.

using System.ComponentModel.DataAnnotations;

namespace SolarMicrogrid.Api.DTOs;

public class CreateBackofficeUserDto
{
    [Required(ErrorMessage = "NIC is required.")]
    [StringLength(12, MinimumLength = 10, ErrorMessage = "NIC must be between 10 and 12 characters.")]
    [RegularExpression(@"^(\d{9}[vVxX]|\d{12})$", ErrorMessage = "Invalid NIC format. Must be 9 digits followed by V/X or 12 digits.")]
    public string NIC { get; set; } = string.Empty;

    [Required(ErrorMessage = "Full Name is required.")]
    [StringLength(100, MinimumLength = 2, ErrorMessage = "Full Name must be between 2 and 100 characters.")]
    public string FullName { get; set; } = string.Empty;

    [Required(ErrorMessage = "Email is required.")]
    [EmailAddress(ErrorMessage = "Invalid email address format.")]
    [StringLength(100, ErrorMessage = "Email cannot exceed 100 characters.")]
    [RegularExpression(@"^[a-zA-Z0-9._%+-]+@gmail\.com$", ErrorMessage = "Email must be a valid @gmail.com address.")]
    public string Email { get; set; } = string.Empty;

    [Required(ErrorMessage = "Phone number is required.")]
    [StringLength(10, MinimumLength = 10, ErrorMessage = "Phone number must be exactly 10 digits.")]
    [RegularExpression(@"^0\d{9}$", ErrorMessage = "Phone number must contain 10 digits and start with 0.")]
    public string Phone { get; set; } = string.Empty;

    [Required(ErrorMessage = "Password is required.")]
    [StringLength(100, MinimumLength = 6, ErrorMessage = "Password must be at least 6 characters long.")]
    public string Password { get; set; } = string.Empty;
}