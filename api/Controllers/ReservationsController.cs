using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Bson;
using MongoDB.Bson.Serialization;
using MongoDB.Driver;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/reservations")]
public class ReservationsController : ControllerBase
{
    private readonly IMongoCollection<EnergyReservation> _reservations;
    private readonly IMongoCollection<BsonDocument> _rawReservations;
    private readonly IMongoCollection<SolarStationInfo> _nodes;
    private readonly IMongoCollection<EnergyBookingSlot> _slots;
    private readonly ILogger<ReservationsController> _logger;

    public ReservationsController(IMongoClient mongoClient, IConfiguration configuration, ILogger<ReservationsController> logger)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _reservations = database.GetCollection<EnergyReservation>("EnergyReservation");
        _rawReservations = database.GetCollection<BsonDocument>("EnergyReservation");
        _nodes = database.GetCollection<SolarStationInfo>("SolarStationInfo");
        _slots = database.GetCollection<EnergyBookingSlot>("EnergyBookingSlots");
        _logger = logger;
    }

    private bool IsProsumerOnly() => User.IsInRole("Prosumer") && !User.IsInRole("Backoffice") && !User.IsInRole("GridOperator");

    private string? GetCurrentNic() => User.Identity?.Name;

    // POST: api/reservations
    // Creates a new energy slot reservation
    [HttpPost]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> CreateReservation(CreateReservationDto dto)
    {
        var loggedInNic = GetCurrentNic();

        if (IsProsumerOnly())
        {
            if (!string.IsNullOrEmpty(dto.ProsumerNic) && !string.Equals(dto.ProsumerNic, loggedInNic, StringComparison.OrdinalIgnoreCase))
            {
                return Forbid();
            }

            dto.ProsumerNic = loggedInNic ?? dto.ProsumerNic;
        }

        if (string.IsNullOrWhiteSpace(dto.ProsumerNic) || string.IsNullOrWhiteSpace(dto.NodeId))
        {
            return BadRequest(new { message = "ProsumerNic and NodeId are required." });
        }

        if (dto.SlotEndTime <= dto.SlotStartTime)
        {
            return BadRequest(new { message = "SlotEndTime must be after SlotStartTime." });
        }

        var now = DateTime.UtcNow;

        if (!ReservationRules.IsWithinBookingWindow(dto.SlotStartTime, now))
        {
            return BadRequest(new { message = "Reservation slot start time must be in the future and within 7 days from now." });
        }

        // Validate node exists and is active
        var node = await _nodes.Find(n => n.Id == dto.NodeId).FirstOrDefaultAsync();
        if (node == null)
        {
            return BadRequest(new { message = $"Microgrid node with id '{dto.NodeId}' was not found." });
        }
        if (!node.IsActive)
        {
            return BadRequest(new { message = $"Microgrid node '{node.StationName}' is currently inactive and cannot accept reservations." });
        }

        var stationName = !string.IsNullOrWhiteSpace(dto.StationName) ? dto.StationName : node.StationName;

        // Find or auto-provision EnergyBookingSlot for this Node and Time Window
        EnergyBookingSlot? slot = null;
        if (!string.IsNullOrWhiteSpace(dto.SlotId))
        {
            slot = await _slots.Find(s => s.Id == dto.SlotId).FirstOrDefaultAsync();
        }

        if (slot == null)
        {
            slot = await _slots.Find(s => s.NodeId == dto.NodeId && s.SlotStartTime == dto.SlotStartTime && s.SlotEndTime == dto.SlotEndTime).FirstOrDefaultAsync();
        }

        if (slot == null)
        {
            // Auto-create default slot for active node if slot doesn't exist yet
            var totalCap = node.CapacityKWh > 0 ? node.CapacityKWh : 100.0;
            var totalSlots = node.TotalBatterySlots > 0 ? node.TotalBatterySlots : 1;
            slot = new EnergyBookingSlot
            {
                NodeId = dto.NodeId,
                StationName = node.StationName,
                SlotStartTime = dto.SlotStartTime,
                SlotEndTime = dto.SlotEndTime,
                TotalCapacityKWh = totalCap,
                AvailableCapacityKWh = totalCap,
                TotalSlotCount = totalSlots,
                AvailableSlotCount = totalSlots,
                Status = "Open",
                ReservationIds = new List<string>(),
                CreatedAt = now,
                UpdatedAt = now
            };
            await _slots.InsertOneAsync(slot);
        }

        // Validate slot availability & capacity
        if (!ReservationRules.HasSufficientSlotCapacity(slot, dto.EnergyKWh))
        {
            if (slot.Status == "Closed")
            {
                return BadRequest(new { message = $"The booking slot for node '{slot.StationName}' is closed." });
            }
            if (slot.AvailableSlotCount <= 0)
            {
                return BadRequest(new { message = $"No battery storage slots available at node '{slot.StationName}' for this time window (0 of {slot.TotalSlotCount} slots available)." });
            }
            if (dto.EnergyKWh > slot.AvailableCapacityKWh)
            {
                return BadRequest(new { message = $"Requested energy ({dto.EnergyKWh} kWh) exceeds remaining available capacity ({slot.AvailableCapacityKWh} kWh) for this slot." });
            }
            return BadRequest(new { message = "The selected slot does not have sufficient capacity or available slots." });
        }

        var reservation = new EnergyReservation
        {
            ProsumerNic = dto.ProsumerNic,
            NodeId = dto.NodeId,
            StationName = stationName ?? string.Empty,
            EnergyKWh = dto.EnergyKWh,
            SlotStartTime = dto.SlotStartTime,
            SlotEndTime = dto.SlotEndTime,
            SlotId = slot.Id,
            Status = ReservationStatus.Pending,
            CreatedAt = now,
            UpdatedAt = now
        };

        await _reservations.InsertOneAsync(reservation);

        // Update booking slot availability and record reservation reference
        var newAvailCap = Math.Max(0, slot.AvailableCapacityKWh - dto.EnergyKWh);
        var newAvailSlots = Math.Max(0, slot.AvailableSlotCount - 1);
        var newStatus = (newAvailSlots == 0 || newAvailCap <= 0) ? "Full" : "Open";

        var slotUpdate = Builders<EnergyBookingSlot>.Update
            .Set(s => s.AvailableCapacityKWh, newAvailCap)
            .Set(s => s.AvailableSlotCount, newAvailSlots)
            .Set(s => s.Status, newStatus)
            .AddToSet(s => s.ReservationIds, reservation.Id!)
            .Set(s => s.UpdatedAt, now);

        await _slots.UpdateOneAsync(s => s.Id == slot.Id, slotUpdate);

        var responseDto = MapToResponseDto(reservation);
        return CreatedAtAction(nameof(GetReservationById), new { id = reservation.Id }, responseDto);
    }

    // GET: api/reservations
    // Returns reservations with optional filtering by prosumerNic, nodeId, and status
    [HttpGet]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetAllReservations(
        [FromQuery] string? prosumerNic,
        [FromQuery] string? nodeId,
        [FromQuery] string? status)
    {
        var filterBuilder = Builders<BsonDocument>.Filter;
        var filter = filterBuilder.Empty;

        var loggedInNic = GetCurrentNic();

        if (IsProsumerOnly())
        {
            if (!string.IsNullOrEmpty(loggedInNic))
            {
                filter &= filterBuilder.Or(
                    filterBuilder.Eq("prosumerNic", loggedInNic),
                    filterBuilder.Eq("nic", loggedInNic)
                );
            }
        }
        else if (!string.IsNullOrWhiteSpace(prosumerNic))
        {
            filter &= filterBuilder.Or(
                filterBuilder.Eq("prosumerNic", prosumerNic),
                filterBuilder.Eq("nic", prosumerNic)
            );
        }

        if (!string.IsNullOrWhiteSpace(nodeId))
        {
            filter &= filterBuilder.Eq("nodeId", nodeId);
        }

        if (!string.IsNullOrWhiteSpace(status))
        {
            if (Enum.TryParse<ReservationStatus>(status, true, out var parsedStatus))
            {
                filter &= filterBuilder.Eq("status", parsedStatus.ToString());
            }
            else
            {
                return BadRequest(new { message = $"Invalid status filter: {status}." });
            }
        }

        var rawDocs = await _rawReservations.Find(filter).ToListAsync();
        var response = new List<ReservationResponseDto>();

        foreach (var doc in rawDocs)
        {
            try
            {
                var reservation = BsonSerializer.Deserialize<EnergyReservation>(doc);
                if (string.IsNullOrEmpty(reservation.ProsumerNic) && doc.Contains("nic") && !doc["nic"].IsBsonNull)
                {
                    reservation.ProsumerNic = doc["nic"].AsString;
                }
                response.Add(MapToResponseDto(reservation));
            }
            catch (Exception ex)
            {
                var docId = doc.Contains("_id") ? doc["_id"].ToString() : "unknown";
                _logger.LogWarning(ex, "Skipped malformed reservation document with ID: {DocId}", docId);
            }
        }

        return Ok(response);
    }

    // GET: api/reservations/{id}
    // Returns a single reservation by ID
    [HttpGet("{id}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetReservationById(string id)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (IsProsumerOnly())
        {
            if (reservation.ProsumerNic != GetCurrentNic())
            {
                return Forbid();
            }
        }

        return Ok(MapToResponseDto(reservation));
    }

    // PUT: api/reservations/{id}
    // Updates slot times for an existing reservation (reschedules slot and manages slot capacity)
    [HttpPut("{id}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> UpdateReservation(string id, UpdateReservationDto dto)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (IsProsumerOnly())
        {
            if (reservation.ProsumerNic != GetCurrentNic())
            {
                return Forbid();
            }
        }

        if (reservation.Status == ReservationStatus.Cancelled || reservation.Status == ReservationStatus.Completed)
        {
            return BadRequest(new { message = $"Cannot update a {reservation.Status.ToString().ToLower()} reservation." });
        }

        var now = DateTime.UtcNow;

        // Enforce 12-hour notice on existing slot
        if (reservation.SlotStartTime.HasValue && !ReservationRules.HasEnoughNotice(reservation.SlotStartTime.Value, now))
        {
            return BadRequest(new { message = "Reservations cannot be modified less than 12 hours before the slot start time." });
        }

        var targetNodeId = !string.IsNullOrWhiteSpace(dto.NodeId) ? dto.NodeId : reservation.NodeId;
        var newStartTime = dto.SlotStartTime ?? reservation.SlotStartTime ?? now;
        var newEndTime = dto.SlotEndTime ?? reservation.SlotEndTime ?? newStartTime.AddHours(1);

        if (newEndTime <= newStartTime)
        {
            return BadRequest(new { message = "SlotEndTime must be after SlotStartTime." });
        }

        if (dto.SlotStartTime.HasValue)
        {
            if (!ReservationRules.IsWithinBookingWindow(newStartTime, now))
            {
                return BadRequest(new { message = "Updated slot start time must be in the future and within 7 days from now." });
            }

            if (!ReservationRules.HasEnoughNotice(newStartTime, now))
            {
                return BadRequest(new { message = "Updated slot start time must be at least 12 hours from now." });
            }
        }

        // Check if node or slot time actually changed
        bool slotChanged = (!string.IsNullOrWhiteSpace(dto.NodeId) && dto.NodeId != reservation.NodeId) ||
                           (dto.SlotStartTime.HasValue && dto.SlotStartTime != reservation.SlotStartTime) ||
                           (dto.SlotEndTime.HasValue && dto.SlotEndTime != reservation.SlotEndTime) ||
                           (!string.IsNullOrWhiteSpace(dto.SlotId) && dto.SlotId != reservation.SlotId);

        var targetNode = await _nodes.Find(n => n.Id == targetNodeId).FirstOrDefaultAsync();
        if (targetNode == null)
        {
            return BadRequest(new { message = $"Microgrid node '{targetNodeId}' not found." });
        }
        if (!targetNode.IsActive)
        {
            return BadRequest(new { message = $"Microgrid node '{targetNode.StationName}' is inactive." });
        }

        string? newSlotId = reservation.SlotId;

        if (slotChanged)
        {
            // Find or auto-create the new slot
            EnergyBookingSlot? newSlot = null;
            if (!string.IsNullOrWhiteSpace(dto.SlotId))
            {
                newSlot = await _slots.Find(s => s.Id == dto.SlotId).FirstOrDefaultAsync();
            }
            if (newSlot == null)
            {
                newSlot = await _slots.Find(s => s.NodeId == targetNodeId && s.SlotStartTime == newStartTime && s.SlotEndTime == newEndTime).FirstOrDefaultAsync();
            }
            if (newSlot == null)
            {
                var totalCap = targetNode.CapacityKWh > 0 ? targetNode.CapacityKWh : 100.0;
                var totalSlots = targetNode.TotalBatterySlots > 0 ? targetNode.TotalBatterySlots : 1;
                newSlot = new EnergyBookingSlot
                {
                    NodeId = targetNodeId,
                    StationName = targetNode.StationName,
                    SlotStartTime = newStartTime,
                    SlotEndTime = newEndTime,
                    TotalCapacityKWh = totalCap,
                    AvailableCapacityKWh = totalCap,
                    TotalSlotCount = totalSlots,
                    AvailableSlotCount = totalSlots,
                    Status = "Open",
                    ReservationIds = new List<string>(),
                    CreatedAt = now,
                    UpdatedAt = now
                };
                await _slots.InsertOneAsync(newSlot);
            }

            // If changing to a DIFFERENT slot, check new slot capacity
            if (newSlot.Id != reservation.SlotId)
            {
                if (!ReservationRules.HasSufficientSlotCapacity(newSlot, reservation.EnergyKWh))
                {
                    if (newSlot.Status == "Closed")
                    {
                        return BadRequest(new { message = $"The target booking slot for node '{newSlot.StationName}' is closed." });
                    }
                    if (newSlot.AvailableSlotCount <= 0)
                    {
                        return BadRequest(new { message = $"No available battery storage slots at node '{newSlot.StationName}' for the requested new time." });
                    }
                    if (reservation.EnergyKWh > newSlot.AvailableCapacityKWh)
                    {
                        return BadRequest(new { message = $"Requested energy ({reservation.EnergyKWh} kWh) exceeds available capacity ({newSlot.AvailableCapacityKWh} kWh) at the target slot." });
                    }
                    return BadRequest(new { message = "The new booking slot does not have sufficient capacity." });
                }

                // 1. Release capacity on OLD slot
                EnergyBookingSlot? oldSlot = null;
                if (!string.IsNullOrEmpty(reservation.SlotId))
                {
                    oldSlot = await _slots.Find(s => s.Id == reservation.SlotId).FirstOrDefaultAsync();
                }
                if (oldSlot == null && !string.IsNullOrEmpty(reservation.NodeId) && reservation.SlotStartTime.HasValue)
                {
                    oldSlot = await _slots.Find(s => s.NodeId == reservation.NodeId && s.SlotStartTime == reservation.SlotStartTime.Value).FirstOrDefaultAsync();
                }

                if (oldSlot != null)
                {
                    var restoredCap = Math.Min(oldSlot.TotalCapacityKWh, oldSlot.AvailableCapacityKWh + reservation.EnergyKWh);
                    var restoredSlots = Math.Min(oldSlot.TotalSlotCount, oldSlot.AvailableSlotCount + 1);
                    var oldStatus = oldSlot.Status == "Closed" ? "Closed" : "Open";

                    var oldSlotUpdate = Builders<EnergyBookingSlot>.Update
                        .Set(s => s.AvailableCapacityKWh, restoredCap)
                        .Set(s => s.AvailableSlotCount, restoredSlots)
                        .Set(s => s.Status, oldStatus)
                        .Pull(s => s.ReservationIds, reservation.Id!)
                        .Set(s => s.UpdatedAt, now);

                    await _slots.UpdateOneAsync(s => s.Id == oldSlot.Id, oldSlotUpdate);
                }

                // 2. Reserve capacity on NEW slot
                var newAvailCap = Math.Max(0, newSlot.AvailableCapacityKWh - reservation.EnergyKWh);
                var newAvailSlots = Math.Max(0, newSlot.AvailableSlotCount - 1);
                var newStatus = (newAvailSlots == 0 || newAvailCap <= 0) ? "Full" : "Open";

                var newSlotUpdate = Builders<EnergyBookingSlot>.Update
                    .Set(s => s.AvailableCapacityKWh, newAvailCap)
                    .Set(s => s.AvailableSlotCount, newAvailSlots)
                    .Set(s => s.Status, newStatus)
                    .AddToSet(s => s.ReservationIds, reservation.Id!)
                    .Set(s => s.UpdatedAt, now);

                await _slots.UpdateOneAsync(s => s.Id == newSlot.Id, newSlotUpdate);
                newSlotId = newSlot.Id;
            }
        }

        var update = Builders<EnergyReservation>.Update
            .Set(r => r.NodeId, targetNodeId)
            .Set(r => r.StationName, targetNode.StationName)
            .Set(r => r.SlotStartTime, newStartTime)
            .Set(r => r.SlotEndTime, newEndTime)
            .Set(r => r.SlotId, newSlotId)
            .Set(r => r.UpdatedAt, now);

        await _reservations.UpdateOneAsync(r => r.Id == id, update);

        var updated = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        return Ok(MapToResponseDto(updated!));
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
            return Conflict(new { message = $"Cannot approve reservation with status '{reservation.Status}'. Only pending reservations can be approved." });
        }

        var now = DateTime.UtcNow;
        var approverId = User.FindFirst(ClaimTypes.NameIdentifier)?.Value ?? User.Identity?.Name ?? "Unknown";

        var update = Builders<EnergyReservation>.Update
            .Set(r => r.Status, ReservationStatus.Approved)
            .Set(r => r.ApprovedAt, now)
            .Set(r => r.ApprovedBy, approverId)
            .Set(r => r.UpdatedAt, now);

        await _reservations.UpdateOneAsync(r => r.Id == id, update);

        var updated = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        return Ok(new { message = "Reservation approved successfully.", reservation = MapToResponseDto(updated!) });
    }

    // PUT: api/reservations/{id}/cancel
    // DELETE: api/reservations/{id}/cancel
    // DELETE: api/reservations/{id}
    // Cancels a reservation, frees slot availability, and records CancelledAt timestamp
    [HttpPut("{id}/cancel")]
    [HttpDelete("{id}/cancel")]
    [HttpDelete("{id}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> CancelReservation(string id)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        if (IsProsumerOnly())
        {
            if (reservation.ProsumerNic != GetCurrentNic())
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

        if (reservation.SlotStartTime.HasValue && !ReservationRules.HasEnoughNotice(reservation.SlotStartTime.Value, now))
        {
            return BadRequest(new { message = "Reservations cannot be cancelled less than 12 hours before the slot start time." });
        }

        // Cancel the reservation
        var update = Builders<EnergyReservation>.Update
            .Set(r => r.Status, ReservationStatus.Cancelled)
            .Set(r => r.CancelledAt, now)
            .Set(r => r.UpdatedAt, now);

        await _reservations.UpdateOneAsync(r => r.Id == id, update);

        // Restore slot availability and remove reservation reference
        EnergyBookingSlot? slot = null;
        if (!string.IsNullOrEmpty(reservation.SlotId))
        {
            slot = await _slots.Find(s => s.Id == reservation.SlotId).FirstOrDefaultAsync();
        }
        if (slot == null && !string.IsNullOrEmpty(reservation.NodeId) && reservation.SlotStartTime.HasValue)
        {
            slot = await _slots.Find(s => s.NodeId == reservation.NodeId && s.SlotStartTime == reservation.SlotStartTime.Value).FirstOrDefaultAsync();
        }

        if (slot != null)
        {
            var restoredCap = Math.Min(slot.TotalCapacityKWh, slot.AvailableCapacityKWh + reservation.EnergyKWh);
            var restoredSlots = Math.Min(slot.TotalSlotCount, slot.AvailableSlotCount + 1);
            var slotStatus = slot.Status == "Closed" ? "Closed" : "Open";

            var slotUpdate = Builders<EnergyBookingSlot>.Update
                .Set(s => s.AvailableCapacityKWh, restoredCap)
                .Set(s => s.AvailableSlotCount, restoredSlots)
                .Set(s => s.Status, slotStatus)
                .Pull(s => s.ReservationIds, reservation.Id!)
                .Set(s => s.UpdatedAt, now);

            await _slots.UpdateOneAsync(s => s.Id == slot.Id, slotUpdate);
        }

        var updated = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();
        return Ok(new { message = "Reservation cancelled successfully.", reservation = MapToResponseDto(updated!) });
    }

    // GET: api/reservations/pending
    // Returns all pending reservations (restricted to prosumer's own if caller is Prosumer)
    [HttpGet("pending")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetPendingReservations()
    {
        var builder = Builders<BsonDocument>.Filter;
        var filter = builder.Eq("status", ReservationStatus.Pending.ToString());

        var loggedInNic = GetCurrentNic();
        if (IsProsumerOnly())
        {
            if (!string.IsNullOrEmpty(loggedInNic))
            {
                filter &= builder.Or(
                    builder.Eq("prosumerNic", loggedInNic),
                    builder.Eq("nic", loggedInNic)
                );
            }
        }

        var rawDocs = await _rawReservations.Find(filter).ToListAsync();
        var response = new List<ReservationResponseDto>();

        foreach (var doc in rawDocs)
        {
            try
            {
                var reservation = BsonSerializer.Deserialize<EnergyReservation>(doc);
                if (string.IsNullOrEmpty(reservation.ProsumerNic) && doc.Contains("nic") && !doc["nic"].IsBsonNull)
                {
                    reservation.ProsumerNic = doc["nic"].AsString;
                }
                response.Add(MapToResponseDto(reservation));
            }
            catch (Exception ex)
            {
                var docId = doc.Contains("_id") ? doc["_id"].ToString() : "unknown";
                _logger.LogWarning(ex, "Skipped malformed pending reservation document with ID: {DocId}", docId);
            }
        }

        return Ok(response);
    }

    // GET: api/reservations/history/{nic}
    // Returns all reservations for the given NIC, ordered by SlotStartTime descending
    [HttpGet("history/{nic}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetReservationHistory(string nic)
    {
        if (IsProsumerOnly())
        {
            if (!string.Equals(nic, GetCurrentNic(), StringComparison.OrdinalIgnoreCase))
            {
                return Forbid();
            }
        }

        var filter = Builders<EnergyReservation>.Filter.Or(
            Builders<EnergyReservation>.Filter.Eq(r => r.ProsumerNic, nic),
            Builders<EnergyReservation>.Filter.Eq("nic", nic)
        );

        var reservations = await _reservations
            .Find(filter)
            .SortByDescending(r => r.SlotStartTime)
            .ToListAsync();

        var response = reservations.Select(MapToResponseDto);
        return Ok(response);
    }

    // GET: api/reservations/dashboard/{nic}
    // Returns activeCount (Approved) and pendingCount (Pending) for the given NIC
    [HttpGet("dashboard/{nic}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetReservationDashboard(string nic)
    {
        if (IsProsumerOnly())
        {
            if (!string.Equals(nic, GetCurrentNic(), StringComparison.OrdinalIgnoreCase))
            {
                return Forbid();
            }
        }

        var nicFilter = Builders<EnergyReservation>.Filter.Or(
            Builders<EnergyReservation>.Filter.Eq(r => r.ProsumerNic, nic),
            Builders<EnergyReservation>.Filter.Eq("nic", nic)
        );

        var activeCount = await _reservations.CountDocumentsAsync(
            nicFilter & Builders<EnergyReservation>.Filter.Eq(r => r.Status, ReservationStatus.Approved));

        var pendingCount = await _reservations.CountDocumentsAsync(
            nicFilter & Builders<EnergyReservation>.Filter.Eq(r => r.Status, ReservationStatus.Pending));

        return Ok(new
        {
            activeCount,
            pendingCount
        });
    }

    // POST: api/reservations/migrate
    // One-time cleanup endpoint to rename `nic` -> `prosumerNic` and backfill missing timestamps
    [HttpPost("migrate")]
    [Authorize(Roles = "Backoffice")]
    public async Task<IActionResult> MigrateLegacyReservations()
    {
        var legacyFilter = Builders<BsonDocument>.Filter.Or(
            Builders<BsonDocument>.Filter.Exists("nic"),
            Builders<BsonDocument>.Filter.Eq("updatedAt", BsonNull.Value),
            Builders<BsonDocument>.Filter.Exists("updatedAt", false),
            Builders<BsonDocument>.Filter.Eq("createdAt", BsonNull.Value),
            Builders<BsonDocument>.Filter.Exists("createdAt", false),
            Builders<BsonDocument>.Filter.Eq("slotStartTime", BsonNull.Value),
            Builders<BsonDocument>.Filter.Exists("slotStartTime", false),
            Builders<BsonDocument>.Filter.Eq("slotEndTime", BsonNull.Value),
            Builders<BsonDocument>.Filter.Exists("slotEndTime", false)
        );

        var matchingDocs = await _rawReservations.Find(legacyFilter).ToListAsync();
        int migratedCount = 0;
        var now = DateTime.UtcNow;

        foreach (var doc in matchingDocs)
        {
            var updateDefinitions = new List<UpdateDefinition<BsonDocument>>();

            // 1. Rename / copy `nic` -> `prosumerNic`
            if (doc.Contains("nic") && !doc["nic"].IsBsonNull)
            {
                var nicValue = doc["nic"].AsString;
                if (!doc.Contains("prosumerNic") || doc["prosumerNic"].IsBsonNull || string.IsNullOrEmpty(doc["prosumerNic"].AsString))
                {
                    updateDefinitions.Add(Builders<BsonDocument>.Update.Set("prosumerNic", nicValue));
                }
                updateDefinitions.Add(Builders<BsonDocument>.Update.Unset("nic"));
            }

            // 2. Backfill createdAt
            if (!doc.Contains("createdAt") || doc["createdAt"].IsBsonNull)
            {
                updateDefinitions.Add(Builders<BsonDocument>.Update.Set("createdAt", now));
            }

            // 3. Backfill updatedAt
            if (!doc.Contains("updatedAt") || doc["updatedAt"].IsBsonNull)
            {
                updateDefinitions.Add(Builders<BsonDocument>.Update.Set("updatedAt", now));
            }

            // 4. Backfill slotStartTime
            DateTime slotStartTimeVal = now;
            if (!doc.Contains("slotStartTime") || doc["slotStartTime"].IsBsonNull)
            {
                updateDefinitions.Add(Builders<BsonDocument>.Update.Set("slotStartTime", slotStartTimeVal));
            }
            else
            {
                try { slotStartTimeVal = doc["slotStartTime"].ToUniversalTime(); } catch { }
            }

            // 5. Backfill slotEndTime
            if (!doc.Contains("slotEndTime") || doc["slotEndTime"].IsBsonNull)
            {
                updateDefinitions.Add(Builders<BsonDocument>.Update.Set("slotEndTime", slotStartTimeVal.AddHours(1)));
            }

            // 6. Unset null qrToken if present
            if (doc.Contains("qrToken") && doc["qrToken"].IsBsonNull)
            {
                updateDefinitions.Add(Builders<BsonDocument>.Update.Unset("qrToken"));
            }

            if (updateDefinitions.Count > 0)
            {
                var combinedUpdate = Builders<BsonDocument>.Update.Combine(updateDefinitions);
                await _rawReservations.UpdateOneAsync(Builders<BsonDocument>.Filter.Eq("_id", doc["_id"]), combinedUpdate);
                migratedCount++;
            }
        }

        return Ok(new
        {
            message = "Legacy reservation documents migration completed.",
            migratedCount
        });
    }

    private static ReservationResponseDto MapToResponseDto(EnergyReservation reservation) => new()
    {
        Id = reservation.Id ?? string.Empty,
        ProsumerNic = reservation.ProsumerNic ?? string.Empty,
        NodeId = reservation.NodeId ?? string.Empty,
        StationName = reservation.StationName ?? string.Empty,
        EnergyKWh = reservation.EnergyKWh,
        SlotStartTime = reservation.SlotStartTime ?? DateTime.UtcNow,
        SlotEndTime = reservation.SlotEndTime ?? (reservation.SlotStartTime ?? DateTime.UtcNow).AddHours(1),
        Status = reservation.Status.ToString(),
        CreatedAt = reservation.CreatedAt ?? DateTime.UtcNow,
        UpdatedAt = reservation.UpdatedAt ?? DateTime.UtcNow,
        CancelledAt = reservation.CancelledAt,
        SlotId = reservation.SlotId
    };
}

