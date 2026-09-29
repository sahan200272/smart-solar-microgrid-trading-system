using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver;
using SolarMicrogrid.Api.Models;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Authorize]
[Route("api/slots")]
public class SlotsController : ControllerBase
{
    private readonly IMongoCollection<EnergyBookingSlot> _slots;
    private readonly IMongoCollection<SolarStationInfo> _nodes;

    public SlotsController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _slots = database.GetCollection<EnergyBookingSlot>("EnergyBookingSlots");
        _nodes = database.GetCollection<SolarStationInfo>("SolarStationInfo");
    }

    // GET: api/slots
    // Query booking slot availability per node, date, or status
    [HttpGet]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetSlots(
        [FromQuery] string? nodeId,
        [FromQuery] DateTime? date,
        [FromQuery] string? status)
    {
        var builder = Builders<EnergyBookingSlot>.Filter;
        var filter = builder.Empty;

        if (!string.IsNullOrWhiteSpace(nodeId))
        {
            filter &= builder.Eq(s => s.NodeId, nodeId);
        }

        if (date.HasValue)
        {
            var dayStart = date.Value.Date.ToUniversalTime();
            var dayEnd = dayStart.AddDays(1);
            filter &= builder.Gte(s => s.SlotStartTime, dayStart) & builder.Lt(s => s.SlotStartTime, dayEnd);
        }

        if (!string.IsNullOrWhiteSpace(status))
        {
            filter &= builder.Eq(s => s.Status, status);
        }

        var slots = await _slots
            .Find(filter)
            .SortBy(s => s.SlotStartTime)
            .ToListAsync();

        return Ok(slots);
    }

    // GET: api/slots/{id}
    [HttpGet("{id}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetSlotById(string id)
    {
        var slot = await _slots.Find(s => s.Id == id).FirstOrDefaultAsync();
        if (slot == null)
        {
            return NotFound(new { message = $"No booking slot found with id {id}." });
        }

        return Ok(slot);
    }

    // GET: api/slots/node/{nodeId}
    [HttpGet("node/{nodeId}")]
    [Authorize(Roles = "Prosumer,Backoffice,GridOperator")]
    public async Task<IActionResult> GetSlotsByNode(string nodeId)
    {
        var slots = await _slots
            .Find(s => s.NodeId == nodeId)
            .SortBy(s => s.SlotStartTime)
            .ToListAsync();

        return Ok(slots);
    }

    // PUT: api/slots/{id}/status
    // Allows Grid Operator or Backoffice to open/close slots or update capacity
    [HttpPut("{id}/status")]
    [Authorize(Roles = "Backoffice,GridOperator")]
    public async Task<IActionResult> UpdateSlotStatus(string id, [FromBody] UpdateSlotStatusRequest request)
    {
        var slot = await _slots.Find(s => s.Id == id).FirstOrDefaultAsync();
        if (slot == null)
        {
            return NotFound(new { message = $"No booking slot found with id {id}." });
        }

        var updateBuilder = Builders<EnergyBookingSlot>.Update;
        var updates = new List<UpdateDefinition<EnergyBookingSlot>>();

        if (!string.IsNullOrWhiteSpace(request.Status))
        {
            updates.Add(updateBuilder.Set(s => s.Status, request.Status));
        }

        if (request.AvailableSlotCount.HasValue)
        {
            var newCount = Math.Clamp(request.AvailableSlotCount.Value, 0, slot.TotalSlotCount);
            updates.Add(updateBuilder.Set(s => s.AvailableSlotCount, newCount));
        }

        if (request.AvailableCapacityKWh.HasValue)
        {
            var newCap = Math.Clamp(request.AvailableCapacityKWh.Value, 0, slot.TotalCapacityKWh);
            updates.Add(updateBuilder.Set(s => s.AvailableCapacityKWh, newCap));
        }

        updates.Add(updateBuilder.Set(s => s.UpdatedAt, DateTime.UtcNow));

        await _slots.UpdateOneAsync(s => s.Id == id, updateBuilder.Combine(updates));

        var updated = await _slots.Find(s => s.Id == id).FirstOrDefaultAsync();
        return Ok(updated);
    }
}

public class UpdateSlotStatusRequest
{
    public string? Status { get; set; }
    public int? AvailableSlotCount { get; set; }
    public double? AvailableCapacityKWh { get; set; }
}
