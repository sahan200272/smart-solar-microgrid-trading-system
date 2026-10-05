// File: api/Models/User.cs
// Component: User & Access Management
// Description: Represents Backoffice, GridOperator, and Prosumer users stored in the MongoDB Users collection.


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
    [BsonElement("nic")]
    public string NIC { get; set; } = string.Empty;

    [BsonElement("fullName")]
    public string FullName { get; set; } = string.Empty;

    [BsonElement("email")]
    public string Email { get; set; } = string.Empty;

    [BsonElement("phone")]
    public string Phone { get; set; } = string.Empty;

    [BsonElement("address")]
    public string Address { get; set; } = string.Empty;

// Password is stored as a BCrypt hash, never as plaintext.
    [BsonElement("passwordHash")]
    public string PasswordHash { get; set; } = string.Empty;

// Backoffice, GridOperator or Prosumer.
    [BsonElement("role")]
    public string Role { get; set; } = string.Empty;

// Pending, Active or Deactivated.
    [BsonElement("status")]
    public string Status { get; set; } = "Pending";

    [BsonElement("createdAt")]
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    [BsonElement("updatedAt")]
    public DateTime? UpdatedAt { get; set; }

}