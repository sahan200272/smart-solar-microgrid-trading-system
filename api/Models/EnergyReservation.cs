using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace SolarMicrogrid.Api.Models;

[BsonIgnoreExtraElements]
public class EnergyReservation
{
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string? Id { get; set; }

    [BsonElement("prosumerNic")]
    public string ProsumerNic { get; set; } = string.Empty;

    [BsonElement("nodeId")]
    public string NodeId { get; set; } = string.Empty;

    [BsonElement("stationName")]
    public string StationName { get; set; } = string.Empty;

    [BsonElement("energyKWh")]
    public double EnergyKWh { get; set; }

    [BsonElement("slotStartTime")]
    public DateTime? SlotStartTime { get; set; }

    [BsonElement("slotEndTime")]
    public DateTime? SlotEndTime { get; set; }

    [BsonElement("status")]
    [BsonRepresentation(BsonType.String)]
    public ReservationStatus Status { get; set; } = ReservationStatus.Pending;

    [BsonElement("createdAt")]
    public DateTime? CreatedAt { get; set; } = DateTime.UtcNow;

    [BsonElement("updatedAt")]
    public DateTime? UpdatedAt { get; set; } = DateTime.UtcNow;

    // Booking view, QR and operator verification details.

    // Optional id of the booked slot.
    [BsonElement("slotId")]
    [BsonIgnoreIfNull]
    public string? SlotId { get; set; }

    [BsonElement("approvedAt")]
    [BsonIgnoreIfNull]
    public DateTime? ApprovedAt { get; set; }

    // Id of the user who approved the reservation.
    [BsonElement("approvedBy")]
    [BsonIgnoreIfNull]
    public string? ApprovedBy { get; set; }

    [BsonElement("cancelledAt")]
    [BsonIgnoreIfNull]
    public DateTime? CancelledAt { get; set; }

    // Single-use token encoded in the QR code. Null until a QR is generated.
    [BsonElement("qrToken")]
    [BsonIgnoreIfNull]
    public string? QrToken { get; set; }

    [BsonElement("qrGeneratedAt")]
    [BsonIgnoreIfNull]
    public DateTime? QrGeneratedAt { get; set; }

    // Time after which the QR token is no longer valid.
    [BsonElement("qrExpiresAt")]
    [BsonIgnoreIfNull]
    public DateTime? QrExpiresAt { get; set; }

    // Set when the QR is used, so it cannot be used again.
    [BsonElement("qrUsedAt")]
    [BsonIgnoreIfNull]
    public DateTime? QrUsedAt { get; set; }

    [BsonElement("completedAt")]
    [BsonIgnoreIfNull]
    public DateTime? CompletedAt { get; set; }

    // Id of the Grid Operator who completed the transfer.
    [BsonElement("completedBy")]
    [BsonIgnoreIfNull]
    public string? CompletedBy { get; set; }

    [BsonElement("completedByNic")]
    [BsonIgnoreIfNull]
    public string? CompletedByNic { get; set; }

    // Energy actually delivered in kWh.
    [BsonElement("deliveredKWh")]
    [BsonIgnoreIfNull]
    public double? DeliveredKWh { get; set; }
}

