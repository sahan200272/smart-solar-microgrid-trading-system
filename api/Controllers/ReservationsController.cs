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

    public ReservationsController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _reservations = database.GetCollection<EnergyReservation>("EnergyReservation");
    }

    // POST: api/reservations
    // Creates a new energy slot reservation
    [HttpPost]
    public async Task<IActionResult> CreateReservation(CreateReservationDto dto)
    {
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

        var reservation = new EnergyReservation
        {
            ProsumerNic = dto.ProsumerNic,
            NodeId = dto.NodeId,
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
    public async Task<IActionResult> GetAllReservations(
        [FromQuery] string? prosumerNic,
        [FromQuery] string? nodeId,
        [FromQuery] string? status)
    {
        var builder = Builders<EnergyReservation>.Filter;
        var filter = builder.Empty;

        if (!string.IsNullOrWhiteSpace(prosumerNic))
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
    public async Task<IActionResult> GetReservationById(string id)
    {
        var reservation = await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync();

        if (reservation == null)
        {
            return NotFound(new { message = $"No reservation found with id {id}." });
        }

        return Ok(MapToResponseDto(reservation));
    }

    private static ReservationResponseDto MapToResponseDto(EnergyReservation reservation) => new()
    {
        Id = reservation.Id ?? string.Empty,
        ProsumerNic = reservation.ProsumerNic,
        NodeId = reservation.NodeId,
        SlotStartTime = reservation.SlotStartTime,
        SlotEndTime = reservation.SlotEndTime,
        Status = reservation.Status.ToString(),
        CreatedAt = reservation.CreatedAt
    };
}
