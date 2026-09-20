using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Api.DTOs;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/[controller]")]
public class NodesController : ControllerBase
{
    private readonly IMongoCollection<SolarStationInfo> _nodes;

    // Constructor: gets the shared MongoDB client via Dependency Injection,
    // then grabs a handle to the "SolarStationInfo" collection specifically
    public NodesController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _nodes = database.GetCollection<SolarStationInfo>("SolarStationInfo");
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

}