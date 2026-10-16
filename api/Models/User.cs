// File: api/Models/User.cs
// Component: User & Access Management
// Description: Represents Backoffice, GridOperator, and Prosumer users stored in the MongoDB Users collection.


using System.ComponentModel.DataAnnotations;
using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace SolarMicrogrid.Api.Models;

public class User
{
//MongoDB will automatically generate the unique userID.
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string? Id { get; set; }

// National Identity Card Number.
// Required as the primary identifier for prosumer accounts.
    [Required(ErrorMessage = "NIC is required.")]
    [StringLength(12, MinimumLength = 10, ErrorMessage = "NIC must be between 10 and 12 characters.")]
    [RegularExpression(@"^(\d{9}[vVxX]|\d{12})$", ErrorMessage = "Invalid NIC format. Must be 9 digits followed by V/X or 12 digits.")]
    [BsonElement("nic")]
    public string NIC { get; set; } = string.Empty;

    [Required(ErrorMessage = "Full Name is required.")]
    [StringLength(100, MinimumLength = 2, ErrorMessage = "Full Name must be between 2 and 100 characters.")]
    [BsonElement("fullName")]
    public string FullName { get; set; } = string.Empty;

    [Required(ErrorMessage = "Email is required.")]
    [EmailAddress(ErrorMessage = "Invalid email address format.")]
    [StringLength(100, ErrorMessage = "Email cannot exceed 100 characters.")]
    [RegularExpression(@"^[a-zA-Z0-9._%+-]+@gmail\.com$", ErrorMessage = "Email must be a valid @gmail.com address.")]
    [BsonElement("email")]
    public string Email { get; set; } = string.Empty;

    [Required(ErrorMessage = "Phone number is required.")]
    [StringLength(10, MinimumLength = 10, ErrorMessage = "Phone number must be exactly 10 digits.")]
    [RegularExpression(@"^0\d{9}$", ErrorMessage = "Phone number must contain 10 digits and start with 0.")]
    [BsonElement("phone")]
    public string Phone { get; set; } = string.Empty;

    [StringLength(250, ErrorMessage = "Address cannot exceed 250 characters.")]
    [BsonElement("address")]
    public string Address { get; set; } = string.Empty;

// Password is stored as a BCrypt hash, never as plaintext.
    [Required]
    [BsonElement("passwordHash")]
    public string PasswordHash { get; set; } = string.Empty;

// Backoffice, GridOperator or Prosumer.
    [Required]
    [StringLength(30)]
    [BsonElement("role")]
    public string Role { get; set; } = string.Empty;

// Pending, Active or Deactivated.
    [Required]
    [StringLength(20)]
    [BsonElement("status")]
    public string Status { get; set; } = "Pending";

    [BsonElement("createdAt")]
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    [BsonElement("updatedAt")]
    public DateTime? UpdatedAt { get; set; }

}