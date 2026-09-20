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

    // Placeholder for now - needed by CreatedAtAction above.
    // We'll build this out properly in Task 3.
    [HttpGet("{id}")]
    public async Task<IActionResult> GetNodeById(string id)
    {
        return Ok(); // temporary - replaced in next task
    }
}