// File:        ReservationQrController.cs
// Component:   Booking Views & Grid Operator Verification
// Description: Handles QR code generation, QR verification and finalisation of
//              energy reservations.
// Author:      Gunathilaka K.K.N.M.
// Endpoints:   POST /api/reservations/{id}/qr
//              POST /api/reservations/verify-qr
//              PUT  /api/reservations/{id}/finalize

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Bson;
using MongoDB.Driver;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using System.Security.Claims;
using System.Security.Cryptography;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/reservations")]
public class ReservationQrController : ControllerBase
{
    private readonly IMongoCollection<EnergyReservation> _reservations;
    private readonly IMongoCollection<User> _users;
    private readonly IMongoCollection<SolarStationInfo> _nodes;

    // Prefix added to the token in the QR payload.
    private const string QrPayloadPrefix = "SSMG:1:";

    private const int TokenSizeInBytes = 32;
    private const int MaxTokenLength = 256;

    // Allowed time (in minutes) after the slot ends and before it starts.
    private const int ExpiryGraceMinutes = 30;
    private const int EarlyScanGraceMinutes = 30;

    // Constructor: gets the MongoDB collections used by the QR workflow.
    public ReservationQrController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);

        _reservations = database.GetCollection<EnergyReservation>("EnergyReservation");
        _users = database.GetCollection<User>("Users");
        _nodes = database.GetCollection<SolarStationInfo>("SolarStationInfo");
    }

    // POST: api/reservations/{id}/qr
    // Generates a QR token for an approved reservation, or returns the existing valid one.
    [HttpPost("{id}/qr")]
    [Authorize(Roles = "Prosumer,Backoffice")]
    public async Task<IActionResult> GenerateQr(string id)
    {
        if (!ObjectId.TryParse(id, out _))
        {
            return BadRequest(new { message = "Invalid reservation id format." });
        }

        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        // Prosumers can only generate a QR for their own reservation
        if (User.IsInRole("Prosumer") && reservation.ProsumerNic != User.Identity?.Name)
        {
            return StatusCode(StatusCodes.Status403Forbidden, new
            {
                message = "You can only generate a QR code for your own reservation."
            });
        }

        if (reservation.QrUsedAt != null)
        {
            return Conflict(new
            {
                reason = "ALREADY_COMPLETED",
                message = "This reservation has already been completed."
            });
        }

        if (reservation.Status != ReservationStatus.Approved)
        {
            return Conflict(new
            {
                reason = "NOT_APPROVED",
                message = $"Only approved reservations can generate a QR code. Current status is {reservation.Status}."
            });
        }

        var now = DateTime.UtcNow;

        // Return the existing token if it has not expired
        if (HasUsableToken(reservation, now))
        {
            return Ok(BuildQrResponse(reservation, reservation.QrToken!, reservation.QrExpiresAt!.Value));
        }

        var slotEndTime = reservation.SlotEndTime ?? (reservation.SlotStartTime ?? now).AddHours(1);
        var expiresAt = slotEndTime.AddMinutes(ExpiryGraceMinutes);

        // A token issued now would already be expired
        if (expiresAt <= now)
        {
            return Conflict(new
            {
                reason = "SLOT_ENDED",
                message = "This booking slot has already ended, so a QR code cannot be generated."
            });
        }

        // Create a new token that expires after the slot ends
        var token = GenerateSecureToken();

        // Only update if nobody else has issued a token since the reservation was read
        var issueFilter = Builders<EnergyReservation>.Filter.And(
            Builders<EnergyReservation>.Filter.Eq(r => r.Id, id),
            Builders<EnergyReservation>.Filter.Eq(r => r.Status, ReservationStatus.Approved),
            Builders<EnergyReservation>.Filter.Eq(r => r.QrUsedAt, null),
            Builders<EnergyReservation>.Filter.Eq(r => r.QrToken, reservation.QrToken)
        );

        var update = Builders<EnergyReservation>.Update
            .Set(r => r.QrToken, token)
            .Set(r => r.QrGeneratedAt, now)
            .Set(r => r.QrExpiresAt, expiresAt)
            .Set(r => r.UpdatedAt, now);

        var result = await _reservations.UpdateOneAsync(issueFilter, update);

        if (result.ModifiedCount == 1)
        {
            return Ok(BuildQrResponse(reservation, token, expiresAt));
        }

        // Another request issued a token first, so return that one instead
        var current = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        if (current != null && current.Status == ReservationStatus.Approved && HasUsableToken(current, now))
        {
            return Ok(BuildQrResponse(current, current.QrToken!, current.QrExpiresAt!.Value));
        }

        return Conflict(new
        {
            reason = "RESERVATION_CHANGED",
            message = "The reservation changed while the QR code was being generated. Please try again."
        });
    }

    // POST: api/reservations/verify-qr
    // Verifies a scanned QR token and returns the reservation details.
    [HttpPost("verify-qr")]
    [Authorize(Roles = "GridOperator")]
    public async Task<IActionResult> VerifyQr(VerifyQrDto dto)
    {
        var token = NormaliseToken(dto?.QrToken);

        if (string.IsNullOrWhiteSpace(token))
        {
            return BadRequest(new
            {
                valid = false,
                reason = "MISSING_TOKEN",
                message = "A QR token is required."
            });
        }

        if (!IsWellFormedToken(token))
        {
            return BadRequest(new
            {
                valid = false,
                reason = "MALFORMED_TOKEN",
                message = "The scanned QR code is not a valid transaction code."
            });
        }

        var reservation = await _reservations.Find(r => r.QrToken == token).FirstOrDefaultAsync();

        if (reservation == null)
        {
            return NotFound(new
            {
                valid = false,
                reason = "INVALID_TOKEN",
                message = "This QR code is not recognised."
            });
        }

        var failure = await EvaluateTokenAsync(reservation, dto?.NodeId);

        if (failure != null)
        {
            return BuildFailureResult(failure);
        }

        var prosumer = await _users
            .Find(u => u.NIC == reservation.ProsumerNic && u.Role == "Prosumer")
            .FirstOrDefaultAsync();

        var station = await _nodes.Find(n => n.Id == reservation.NodeId).FirstOrDefaultAsync();

        return Ok(new
        {
            valid = true,
            reservationId = reservation.Id,
            prosumer = new
            {
                nic = reservation.ProsumerNic,
                fullName = prosumer?.FullName ?? reservation.ProsumerNic,
                phone = prosumer?.Phone ?? string.Empty
            },
            station = new
            {
                id = reservation.NodeId,
                name = station?.StationName ?? reservation.StationName
            },
            slotStartTime = reservation.SlotStartTime,
            slotEndTime = reservation.SlotEndTime,
            energyKWh = reservation.EnergyKWh,
            status = reservation.Status.ToString()
        });
    }

    // PUT: api/reservations/{id}/finalize
    // Marks the reservation as completed after the QR token has been verified.
    [HttpPut("{id}/finalize")]
    [Authorize(Roles = "GridOperator")]
    public async Task<IActionResult> FinalizeReservation(string id, FinalizeReservationDto dto)
    {
        if (!ObjectId.TryParse(id, out _))
        {
            return BadRequest(new { message = "Invalid reservation id format." });
        }

        var token = NormaliseToken(dto?.QrToken);

        if (string.IsNullOrWhiteSpace(token))
        {
            return BadRequest(new
            {
                reason = "MISSING_TOKEN",
                message = "A QR token is required to finalise an energy transfer."
            });
        }

        if (dto?.DeliveredKWh != null && dto.DeliveredKWh <= 0)
        {
            return BadRequest(new { message = "Delivered energy must be greater than zero." });
        }

        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        // The token must belong to this reservation
        if (reservation.QrToken != token)
        {
            return Conflict(new
            {
                reason = "TOKEN_MISMATCH",
                message = "This QR code does not belong to the reservation being finalised."
            });
        }

        var failure = await EvaluateTokenAsync(reservation, dto?.NodeId);

        if (failure != null)
        {
            return BuildFailureResult(failure);
        }

        // Delivered energy cannot be more than 10% above the reserved amount.
        // Skipped when no amount was reserved (EnergyKWh is 0).
        if (dto?.DeliveredKWh != null
            && reservation.EnergyKWh > 0
            && dto.DeliveredKWh > reservation.EnergyKWh * 1.1)
        {
            return BadRequest(new
            {
                message = $"Delivered energy cannot exceed the reserved amount ({reservation.EnergyKWh} kWh) by more than 10%."
            });
        }

        var now = DateTime.UtcNow;

        // Get the operator details from the logged-in user
        var operatorId = User.FindFirst(ClaimTypes.NameIdentifier)?.Value;
        var operatorNic = User.Identity?.Name;

        // Only update if the reservation is still approved and the QR is unused
        var finalizeFilter = Builders<EnergyReservation>.Filter.And(
            Builders<EnergyReservation>.Filter.Eq(r => r.Id, id),
            Builders<EnergyReservation>.Filter.Eq(r => r.QrToken, token),
            Builders<EnergyReservation>.Filter.Eq(r => r.Status, ReservationStatus.Approved),
            Builders<EnergyReservation>.Filter.Eq(r => r.QrUsedAt, null)
        );

        var finalizeUpdate = Builders<EnergyReservation>.Update
            .Set(r => r.Status, ReservationStatus.Completed)
            .Set(r => r.QrUsedAt, now)
            .Set(r => r.CompletedAt, now)
            .Set(r => r.CompletedBy, operatorId)
            .Set(r => r.CompletedByNic, operatorNic)
            .Set(r => r.DeliveredKWh, dto?.DeliveredKWh)
            .Set(r => r.UpdatedAt, now);

        var result = await _reservations.UpdateOneAsync(finalizeFilter, finalizeUpdate);

        if (result.ModifiedCount != 1)
        {
            return await ResolveFailureAfterNoMatchAsync(id);
        }

        var updated = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        return Ok(new
        {
            message = "Energy transfer finalised successfully.",
            reservation = new
            {
                id = updated!.Id,
                nic = updated.ProsumerNic,
                stationName = updated.StationName,
                status = updated.Status.ToString(),
                slotStartTime = updated.SlotStartTime,
                slotEndTime = updated.SlotEndTime,
                energyKWh = updated.EnergyKWh,
                deliveredKWh = updated.DeliveredKWh,
                completedAt = updated.CompletedAt,
                completedBy = updated.CompletedBy,
                completedByNic = updated.CompletedByNic
            }
        });
    }

    // Checks whether a reservation's QR token can be used. Returns null if it is valid.
    private async Task<QrFailure?> EvaluateTokenAsync(EnergyReservation reservation, string? nodeId)
    {
        var now = DateTime.UtcNow;

        if (reservation.QrUsedAt != null)
        {
            return new QrFailure(StatusCodes.Status409Conflict, "ALREADY_USED",
                "This QR code has already been used.");
        }

        if (reservation.QrExpiresAt == null || now > reservation.QrExpiresAt)
        {
            return new QrFailure(StatusCodes.Status400BadRequest, "EXPIRED",
                "This QR code has expired.");
        }

        if (reservation.Status != ReservationStatus.Approved)
        {
            return new QrFailure(StatusCodes.Status409Conflict, "NOT_APPROVED",
                $"This reservation is not approved. Current status is {reservation.Status}.");
        }

        if (reservation.SlotStartTime.HasValue && now < reservation.SlotStartTime.Value.AddMinutes(-EarlyScanGraceMinutes))
        {
            return new QrFailure(StatusCodes.Status400BadRequest, "TOO_EARLY",
                $"This booking starts at {reservation.SlotStartTime:u}. It cannot be processed yet.");
        }

        // Reject if the booking is for a different node
        if (!string.IsNullOrWhiteSpace(nodeId) && reservation.NodeId != nodeId)
        {
            return new QrFailure(StatusCodes.Status409Conflict, "WRONG_NODE",
                "This booking belongs to a different microgrid node.");
        }

        // The prosumer account must exist and be active
        var prosumer = await _users
            .Find(u => u.NIC == reservation.ProsumerNic && u.Role == "Prosumer")
            .FirstOrDefaultAsync();

        if (prosumer == null)
        {
            return new QrFailure(StatusCodes.Status409Conflict, "PROSUMER_NOT_FOUND",
                "The prosumer account for this booking no longer exists.");
        }

        if (prosumer.Status != "Active")
        {
            return new QrFailure(StatusCodes.Status409Conflict, "PROSUMER_INACTIVE",
                $"The prosumer account is {prosumer.Status.ToLower()}.");
        }

        return null;
    }

    // Checks whether the reservation has an unused token that has not expired.
    private static bool HasUsableToken(EnergyReservation reservation, DateTime now)
    {
        return !string.IsNullOrEmpty(reservation.QrToken)
            && reservation.QrUsedAt == null
            && reservation.QrExpiresAt != null
            && reservation.QrExpiresAt > now;
    }

    // Returns the reason a finalise update did not modify the reservation.
    private async Task<IActionResult> ResolveFailureAfterNoMatchAsync(string id)
    {
        var current = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        if (current == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (current.QrUsedAt != null)
        {
            return Conflict(new
            {
                reason = "ALREADY_USED",
                message = "This energy transfer has already been finalised."
            });
        }

        return Conflict(new
        {
            reason = "NOT_APPROVED",
            message = $"This reservation cannot be finalised. Current status is {current.Status}."
        });
    }

    // Generates a random URL-safe token for the QR code.
    private static string GenerateSecureToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(TokenSizeInBytes);

        // Convert to Base64Url format
        return Convert.ToBase64String(bytes)
            .Replace('+', '-')
            .Replace('/', '_')
            .TrimEnd('=');
    }

    // Removes the QR payload prefix and returns only the token.
    private static string NormaliseToken(string? scannedValue)
    {
        if (string.IsNullOrWhiteSpace(scannedValue))
        {
            return string.Empty;
        }

        var trimmed = scannedValue.Trim();

        return trimmed.StartsWith(QrPayloadPrefix, StringComparison.Ordinal)
            ? trimmed[QrPayloadPrefix.Length..]
            : trimmed;
    }

    // Checks that the token has a valid length and only Base64Url characters.
    private static bool IsWellFormedToken(string token)
    {
        if (token.Length > MaxTokenLength)
        {
            return false;
        }

        foreach (var character in token)
        {
            // ASCII only: char.IsLetterOrDigit would also accept letters such as 'é'
            var isBase64UrlCharacter = char.IsAsciiLetterOrDigit(character)
                || character == '-'
                || character == '_';

            if (!isBase64UrlCharacter)
            {
                return false;
            }
        }

        return true;
    }

    // Builds the response returned after generating a QR code.
    private object BuildQrResponse(EnergyReservation reservation, string token, DateTime expiresAt)
    {
        return new
        {
            reservationId = reservation.Id,
            qrToken = token,

            // Value to encode in the QR image
            qrPayload = QrPayloadPrefix + token,
            expiresAt,
            slotStartTime = reservation.SlotStartTime,
            slotEndTime = reservation.SlotEndTime,
            stationName = reservation.StationName,
            energyKWh = reservation.EnergyKWh,
            status = reservation.Status.ToString()
        };
    }

    // Converts a QR validation failure into an HTTP response.
    private IActionResult BuildFailureResult(QrFailure failure)
    {
        return StatusCode(failure.StatusCode, new
        {
            valid = false,
            reason = failure.Reason,
            message = failure.Message
        });
    }

    // Holds the details of a failed QR validation.
    private sealed record QrFailure(int StatusCode, string Reason, string Message);
}
