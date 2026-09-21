using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver;
using MongoDB.Bson;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Api.DTOs;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/[controller]")]
public class NodesController : ControllerBase
{
    private readonly IMongoCollection<SolarStationInfo> _nodes;
    private readonly IMongoCollection<BsonDocument> _reservations;

    // Constructor: gets the shared MongoDB client via Dependency Injection,
    // then grabs a handle to the "SolarStationInfo" collection specifically
    public NodesController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _nodes = database.GetCollection<SolarStationInfo>("SolarStationInfo");

        _reservations = database.GetCollection<BsonDocument>("EnergyReservation");
    }

    // POST api/nodes
    // Creates a new microgrid node (hub)
    [HttpPost]
    public async Task<IActionResult> CreateNode(CreateNodeDto dto)
    {
        // Basic validation - reject obviously bad input before touching the DB
        if (string.IsNullOrWhiteSpace(dto.StationName))
        {
            return BadRequest(new { message = "Station name is required." });
        }

        if (dto.TotalBatterySlots <= 0)
        {
            return BadRequest(new { message = "Total battery slots must be greater than zero." });
        }

        // Map the incoming DTO into the actual database model.
        // Server controls these fields, not the client.
        var newNode = new SolarStationInfo
        {
            StationName = dto.StationName,
            Latitude = dto.Latitude,
            Longitude = dto.Longitude,
            CapacityKWh = dto.CapacityKWh,
            TotalBatterySlots = dto.TotalBatterySlots,
            AvailableBatterySlots = dto.TotalBatterySlots, // starts fully available
            OperatingSchedule = dto.OperatingSchedule,
            IsActive = true,
            CreatedAt = DateTime.UtcNow
        };

        await _nodes.InsertOneAsync(newNode);

        // 201 Created is the correct HTTP status for successful creation,
        // and includes a Location header pointing to the new resource
        return CreatedAtAction(nameof(GetNodeById), new { id = newNode.Id }, newNode);
    }

    // GET api/nodes
    // Returns all nodes - used by the Backoffice web admin list screen
    [HttpGet]
    public async Task<IActionResult> GetAllNodes()
    {
        var nodes = await _nodes.Find(_ => true).ToListAsync();
        return Ok(nodes);
    }

    // GET api/nodes/{id}
    // Returns a single node by its MongoDB ID
    [HttpGet("{id}")]
    public async Task<IActionResult> GetNodeById(string id)
    {
        var node = await _nodes.Find(n => n.Id == id).FirstOrDefaultAsync();

        if (node == null)
        {
            return NotFound(new { message = $"No node found with id {id}." });
        }

        return Ok(node);
    }

    // GET api/nodes/nearby?lat=7.2083&lng=79.8358&radiusKm=10
    // Returns nodes within a given radius of a GPS point - used by mobile map feature
    [HttpGet("nearby")]
    public async Task<IActionResult> GetNearbyNodes([FromQuery] double lat, [FromQuery] double lng, [FromQuery] double radiusKm = 10)
    {
        // Get all active nodes first, then filter by distance in memory.
        // (Fine for a student project's data scale - a production system
        // would use MongoDB's geospatial indexes instead.)
        var allNodes = await _nodes.Find(n => n.IsActive).ToListAsync();

        var nearbyNodes = allNodes
            .Where(n => CalculateDistanceKm(lat, lng, n.Latitude, n.Longitude) <= radiusKm)
            .ToList();

        return Ok(nearbyNodes);
    }

    // Haversine formula - calculates straight-line distance in km between two GPS points
    private static double CalculateDistanceKm(double lat1, double lon1, double lat2, double lon2)
    {
        const double earthRadiusKm = 6371;

        var dLat = ToRadians(lat2 - lat1);
        var dLon = ToRadians(lon2 - lon1);

        var a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2) +
                Math.Cos(ToRadians(lat1)) * Math.Cos(ToRadians(lat2)) *
                Math.Sin(dLon / 2) * Math.Sin(dLon / 2);

        var c = 2 * Math.Atan2(Math.Sqrt(a), Math.Sqrt(1 - a));

        return earthRadiusKm * c;
    }

    private static double ToRadians(double degrees) => degrees * Math.PI / 180;

    // PUT api/nodes/{id}
    // Updates an existing node's schedule/specs
    [HttpPut("{id}")]
    public async Task<IActionResult> UpdateNode(string id, UpdateNodeDto dto)
    {
        // Basic validation - same rules as create
        if (string.IsNullOrWhiteSpace(dto.StationName))
        {
            return BadRequest(new { message = "Station name is required." });
        }

        if (dto.TotalBatterySlots <= 0)
        {
            return BadRequest(new { message = "Total battery slots must be greater than zero." });
        }

        // First, confirm the node actually exists
        var existingNode = await _nodes.Find(n => n.Id == id).FirstOrDefaultAsync();

        if (existingNode == null)
        {
            return NotFound(new { message = $"No node found with id {id}." });
        }

        // If total battery slots changed, adjust available slots proportionally
        // so we don't accidentally show more available slots than the new total allows.
        var slotDifference = dto.TotalBatterySlots - existingNode.TotalBatterySlots;
        var newAvailableSlots = existingNode.AvailableBatterySlots + slotDifference;

        // Never let available slots go negative or exceed the new total
        newAvailableSlots = Math.Clamp(newAvailableSlots, 0, dto.TotalBatterySlots);

        // Build the update - only touching the fields the client is allowed to change
        var update = Builders<SolarStationInfo>.Update
            .Set(n => n.StationName, dto.StationName)
            .Set(n => n.Latitude, dto.Latitude)
            .Set(n => n.Longitude, dto.Longitude)
            .Set(n => n.CapacityKWh, dto.CapacityKWh)
            .Set(n => n.TotalBatterySlots, dto.TotalBatterySlots)
            .Set(n => n.AvailableBatterySlots, newAvailableSlots)
            .Set(n => n.OperatingSchedule, dto.OperatingSchedule);

        await _nodes.UpdateOneAsync(n => n.Id == id, update);

        // Return the updated node so the caller can confirm the changes
        var updatedNode = await _nodes.Find(n => n.Id == id).FirstOrDefaultAsync();
        return Ok(updatedNode);
    }

    // PUT api/nodes/{id}/deactivate
    // Deactivates a node - but only if it has no active/pending reservations
    [HttpPut("{id}/deactivate")]
    public async Task<IActionResult> DeactivateNode(string id)
    {
        var existingNode = await _nodes.Find(n => n.Id == id).FirstOrDefaultAsync();

        if (existingNode == null)
        {
            return NotFound(new { message = $"No node found with id {id}." });
        }

        if (!existingNode.IsActive)
        {
            return BadRequest(new { message = "This node is already deactivated." });
        }

        // Check for active/pending reservations tied to this node.
        // Adjust "nodeId" and "status" field names below once confirmed with Member C.
        var activeReservationFilter = Builders<BsonDocument>.Filter.And(
            Builders<BsonDocument>.Filter.Eq("nodeId", id),
            Builders<BsonDocument>.Filter.In("status", new[] { "Pending", "Approved" })
        );

        var activeReservationCount = await _reservations.CountDocumentsAsync(activeReservationFilter);

        if (activeReservationCount > 0)
        {
            return BadRequest(new
            {
                message = $"Cannot deactivate this node - {activeReservationCount} active reservation(s) exist.",
                activeReservations = activeReservationCount
            });
        }

        // No blocking reservations - safe to deactivate
        var update = Builders<SolarStationInfo>.Update.Set(n => n.IsActive, false);
        await _nodes.UpdateOneAsync(n => n.Id == id, update);

        var updatedNode = await _nodes.Find(n => n.Id == id).FirstOrDefaultAsync();
        return Ok(new { message = "Node deactivated successfully.", node = updatedNode });
    }

}