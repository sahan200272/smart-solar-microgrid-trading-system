using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/reservations")]
public class ReservationsController : ControllerBase
{
    private readonly IMongoCollection<EnergyReservation> _reservations;
    private readonly IMongoCollection<SolarStationInfo> _nodes;

    public ReservationsController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _reservations = database.GetCollection<EnergyReservation>("EnergyReservation");
        _nodes = database.GetCollection<SolarStationInfo>("SolarStationInfo");
    }

    // POST: api/reservations
    // Creates a new energy slot reservation
    [HttpPost]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> CreateReservation(CreateReservationDto dto)
    {
        if (User.IsInRole("Prosumer"))
        {
            var loggedInNic = User.Identity?.Name;
            if (!string.IsNullOrEmpty(loggedInNic) && loggedInNic != dto.ProsumerNic)
            {
                return Forbid();
            }
        }

        if (string.IsNullOrWhiteSpace(dto.ProsumerNic) || string.IsNullOrWhiteSpace(dto.NodeId))
        {
            return BadRequest(new { message = "ProsumerNic and NodeId are required." });
        }

        if (dto.SlotEndTime <= dto.SlotStartTime)
        {
            return BadRequest(new { message = "SlotEndTime must be after SlotStartTime." });
        }

        if (!ReservationRules.IsWithinBookingWindow(dto.SlotStartTime, DateTime.UtcNow))
        {
            return BadRequest(new { message = "Reservation slot start time must be in the future and within 7 days from now." });
        }

        var stationName = dto.StationName;
        if (string.IsNullOrWhiteSpace(stationName))
        {
            var node = await _nodes.Find(n => n.Id == dto.NodeId).FirstOrDefaultAsync();
            if (node != null)
            {
                stationName = node.StationName;
            }
        }

        var reservation = new EnergyReservation
        {
            ProsumerNic = dto.ProsumerNic,
            NodeId = dto.NodeId,
            StationName = stationName ?? string.Empty,
            EnergyKWh = dto.EnergyKWh,
            SlotStartTime = dto.SlotStartTime,
            SlotEndTime = dto.SlotEndTime,
            Status = ReservationStatus.Pending,
            CreatedAt = DateTime.UtcNow,
            UpdatedAt = DateTime.UtcNow
        };

        await _reservations.InsertOneAsync(reservation);

        var responseDto = MapToResponseDto(reservation);
        return CreatedAtAction(nameof(GetReservationById), new { id = reservation.Id }, responseDto);
    }

    // GET: api/reservations
    // Returns reservations with optional filtering by prosumerNic, nodeId, and status
    [HttpGet]
    [Authorize(Roles = "Backoffice,GridOperator,Prosumer")]
    public async Task<IActionResult> GetAllReservations(
        [FromQuery] string? prosumerNic,
        [FromQuery] string? nodeId,
        [FromQuery] string? status)
    {
        var builder = Builders<EnergyReservation>.Filter;
        var filter = builder.Empty;

        if (User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator"))
        {
            var loggedInNic = User.Identity?.Name;
            if (!string.IsNullOrEmpty(loggedInNic))
            {
                filter &= builder.Eq(r => r.ProsumerNic, loggedInNic);
            }
        }
        else if (!string.IsNullOrWhiteSpace(prosumerNic))
        {
            filter &= builder.Eq(r => r.ProsumerNic, prosumerNic);
        }

        if (!string.IsNullOrWhiteSpace(nodeId))
        {
            filter &= builder.Eq(r => r.NodeId, nodeId);
        }

        if (!string.IsNullOrWhiteSpace(status))
        {
            if (Enum.TryParse<ReservationStatus>(status, true, out var parsedStatus))
            {
                filter &= builder.Eq(r => r.Status, parsedStatus);
            }
            else
            {
                return BadRequest(new { message = $"Invalid status filter: {status}." });
            }
        }

        var reservations = await _reservations.Find(filter).ToListAsync();
        var response = reservations.Select(MapToResponseDto);
        return Ok(response);
    }

    // GET: api/reservations/{id}
    // Returns a single reservation by ID
    [HttpGet("{id}")]
    [Authorize(Roles = "Backoffice,GridOperator,Prosumer")]
    public async Task<IActionResult> GetReservationById(string id)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator"))
        {
            if (reservation.ProsumerNic != User.Identity?.Name)
            {
                return Forbid();
            }
        }

        return Ok(MapToResponseDto(reservation));
    }

    // PUT: api/reservations/{id}
    // Updates slot times for an existing reservation
    [HttpPut("{id}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> UpdateReservation(string id, UpdateReservationDto dto)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator"))
        {
            if (reservation.ProsumerNic != User.Identity?.Name)
            {
                return Forbid();
            }
        }

        if (reservation.Status == ReservationStatus.Cancelled || reservation.Status == ReservationStatus.Completed)
        {
            return BadRequest(new { message = $"Cannot update a {reservation.Status.ToString().ToLower()} reservation." });
        }

        var newStartTime = dto.SlotStartTime ?? reservation.SlotStartTime;
        var newEndTime = dto.SlotEndTime ?? reservation.SlotEndTime;

        if (newEndTime <= newStartTime)
        {
            return BadRequest(new { message = "SlotEndTime must be after SlotStartTime." });
        }

        if (dto.SlotStartTime.HasValue && !ReservationRules.IsWithinBookingWindow(newStartTime, DateTime.UtcNow))
        {
            return BadRequest(new { message = "Updated slot start time must be in the future and within 7 days from now." });
        }

        var update = Builders<EnergyReservation>.Update
            .Set(r => r.SlotStartTime, newStartTime)
            .Set(r => r.SlotEndTime, newEndTime)
            .Set(r => r.UpdatedAt, DateTime.UtcNow);

        await _reservations.UpdateOneAsync(r => r.Id == id, update);

        var updated = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        return Ok(MapToResponseDto(updated));
    }

    // PUT: api/reservations/{id}/approve
    // Approves a pending reservation (Operator / Backoffice only)
    [HttpPut("{id}/approve")]
    [Authorize(Roles = "Backoffice,GridOperator")]
    public async Task<IActionResult> ApproveReservation(string id)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (reservation.Status != ReservationStatus.Pending)
        {
            return BadRequest(new { message = $"Cannot approve reservation with status '{reservation.Status}'." });
        }

        var update = Builders<EnergyReservation>.Update
            .Set(r => r.Status, ReservationStatus.Approved)
            .Set(r => r.UpdatedAt, DateTime.UtcNow);

        await _reservations.UpdateOneAsync(r => r.Id == id, update);

        var updated = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        return Ok(new { message = "Reservation approved successfully.", reservation = MapToResponseDto(updated) });
    }

    // PUT: api/reservations/{id}/cancel
    // Cancels a reservation and records CancelledAt timestamp
    [HttpPut("{id}/cancel")]
    [HttpDelete("{id}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> CancelReservation(string id)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator"))
        {
            if (reservation.ProsumerNic != User.Identity?.Name)
            {
                return Forbid();
            }
        }

        if (reservation.Status == ReservationStatus.Cancelled)
        {
            return BadRequest(new { message = "Reservation is already cancelled." });
        }

        if (reservation.Status == ReservationStatus.Completed)
        {
            return BadRequest(new { message = "Cannot cancel a completed reservation." });
        }

        var now = DateTime.UtcNow;
        var update = Builders<EnergyReservation>.Update
            .Set(r => r.Status, ReservationStatus.Cancelled)
            .Set(r => r.CancelledAt, now)
            .Set(r => r.UpdatedAt, now);

        await _reservations.UpdateOneAsync(r => r.Id == id, update);

        var updated = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        return Ok(new { message = "Reservation cancelled successfully.", reservation = MapToResponseDto(updated) });
    }

    // GET: api/reservations/pending
    // Returns all pending reservations (restricted to prosumer's own if caller is Prosumer)
    [HttpGet("pending")]
    [Authorize(Roles = "Backoffice,GridOperator,Prosumer")]
    public async Task<IActionResult> GetPendingReservations()
    {
        var builder = Builders<EnergyReservation>.Filter;
        var filter = builder.Eq(r => r.Status, ReservationStatus.Pending);

        if (User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator"))
        {
            var loggedInNic = User.Identity?.Name;
            if (!string.IsNullOrEmpty(loggedInNic))
            {
                filter &= builder.Eq(r => r.ProsumerNic, loggedInNic);
            }
        }

        var reservations = await _reservations.Find(filter).ToListAsync();
        var response = reservations.Select(MapToResponseDto);
        return Ok(response);
    }

    // GET: api/reservations/history/{nic}
    // Returns all reservations for the given NIC, ordered by SlotStartTime descending
    [HttpGet("history/{nic}")]
    [Authorize(Roles = "Backoffice,GridOperator,Prosumer")]
    public async Task<IActionResult> GetReservationHistory(string nic)
    {
        if (User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator"))
        {
            if (nic != User.Identity?.Name)
            {
                return Forbid();
            }
        }

        var reservations = await _reservations
            .Find(r => r.ProsumerNic == nic)
            .SortByDescending(r => r.SlotStartTime)
            .ToListAsync();

        var response = reservations.Select(MapToResponseDto);
        return Ok(response);
    }

    // GET: api/reservations/dashboard/{nic}
    // Returns activeCount (Approved) and pendingCount (Pending) for the given NIC
    [HttpGet("dashboard/{nic}")]
    [Authorize(Roles = "Backoffice,GridOperator,Prosumer")]
    public async Task<IActionResult> GetReservationDashboard(string nic)
    {
        if (User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator"))
        {
            if (nic != User.Identity?.Name)
            {
                return Forbid();
            }
        }

        var activeCount = await _reservations.CountDocumentsAsync(r => r.ProsumerNic == nic && r.Status == ReservationStatus.Approved);
        var pendingCount = await _reservations.CountDocumentsAsync(r => r.ProsumerNic == nic && r.Status == ReservationStatus.Pending);

        return Ok(new
        {
            activeCount,
            pendingCount
        });
    }

    private static ReservationResponseDto MapToResponseDto(EnergyReservation reservation) => new()
    {
        Id = reservation.Id ?? string.Empty,
        ProsumerNic = reservation.ProsumerNic,
        NodeId = reservation.NodeId,
        StationName = reservation.StationName,
        EnergyKWh = reservation.EnergyKWh,
        SlotStartTime = reservation.SlotStartTime,
        SlotEndTime = reservation.SlotEndTime,
        Status = reservation.Status.ToString(),
        CreatedAt = reservation.CreatedAt,
        UpdatedAt = reservation.UpdatedAt,
        CancelledAt = reservation.CancelledAt
    };
}

