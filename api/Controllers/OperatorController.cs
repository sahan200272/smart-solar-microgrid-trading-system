// File:        OperatorController.cs
// Component:   Booking Views & Grid Operator Verification
// Description: Provides the Grid Operator dashboard with booking counts and preview lists.
// Author:      Gunathilaka K.K.N.M.
// Endpoints:   GET /api/operator/dashboard
//              GET /api/operator/stations

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Bson;
using MongoDB.Driver;
using SolarMicrogrid.Api.Models;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/operator")]
[Authorize(Roles = "GridOperator,Backoffice")]
public class OperatorController : ControllerBase
{
    private readonly IMongoCollection<EnergyReservation> _reservations;
    private readonly IMongoCollection<User> _users;
    private readonly IMongoCollection<SolarStationInfo> _nodes;

    // Maximum number of rows returned in each preview list.
    private const int PreviewLimit = 10;

    // Constructor: gets the MongoDB collections used by the dashboard.
    public OperatorController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);

        _reservations = database.GetCollection<EnergyReservation>("EnergyReservation");
        _users = database.GetCollection<User>("Users");
        _nodes = database.GetCollection<SolarStationInfo>("SolarStationInfo");
    }

    // GET: api/operator/dashboard?nodeId={optional}
    // Returns booking counts and pending/today previews, optionally filtered by node.
    [HttpGet("dashboard")]
    public async Task<IActionResult> GetOperatorDashboard([FromQuery] string? nodeId)
    {
        // Validate the optional node id
        if (!string.IsNullOrWhiteSpace(nodeId) && !ObjectId.TryParse(nodeId, out _))
        {
            return BadRequest(new { message = "Invalid node id format." });
        }

        var now = DateTime.UtcNow;
        var todayStart = now.Date;
        var todayEnd = todayStart.AddDays(1);

        var filter = Builders<EnergyReservation>.Filter;

        // Filter by node only when a node id is given
        var baseFilter = string.IsNullOrWhiteSpace(nodeId)
            ? filter.Empty
            : filter.Eq(r => r.NodeId, nodeId);

        var pendingFilter = baseFilter & filter.Eq(r => r.Status, "Pending");

        // Approved reservations with a future start time
        var approvedFutureFilter = baseFilter
            & filter.Eq(r => r.Status, "Approved")
            & filter.Gt(r => r.SlotStartTime, now);

        // Approved reservations scheduled for today
        var activeTodayFilter = baseFilter
            & filter.Eq(r => r.Status, "Approved")
            & filter.Gte(r => r.SlotStartTime, todayStart)
            & filter.Lt(r => r.SlotStartTime, todayEnd);

        var completedTodayFilter = baseFilter
            & filter.Eq(r => r.Status, "Completed")
            & filter.Gte(r => r.CompletedAt, todayStart)
            & filter.Lt(r => r.CompletedAt, todayEnd);

        var cancelledTodayFilter = baseFilter
            & filter.Eq(r => r.Status, "Cancelled")
            & filter.Gte(r => r.CancelledAt, todayStart)
            & filter.Lt(r => r.CancelledAt, todayEnd);

        var pendingCount = await _reservations.CountDocumentsAsync(pendingFilter);
        var approvedFutureCount = await _reservations.CountDocumentsAsync(approvedFutureFilter);
        var activeTodayCount = await _reservations.CountDocumentsAsync(activeTodayFilter);
        var completedTodayCount = await _reservations.CountDocumentsAsync(completedTodayFilter);
        var cancelledTodayCount = await _reservations.CountDocumentsAsync(cancelledTodayFilter);

        // Preview lists sorted by slot start time
        var sortBySlot = Builders<EnergyReservation>.Sort.Ascending(r => r.SlotStartTime);

        var pendingPreview = await _reservations
            .Find(pendingFilter)
            .Sort(sortBySlot)
            .Limit(PreviewLimit)
            .ToListAsync();

        var todayPreview = await _reservations
            .Find(activeTodayFilter)
            .Sort(sortBySlot)
            .Limit(PreviewLimit)
            .ToListAsync();

        var nameByNic = await BuildProsumerNameLookupAsync(pendingPreview, todayPreview);

        return Ok(new
        {
            generatedAt = now,
            nodeId = string.IsNullOrWhiteSpace(nodeId) ? null : nodeId,
            counts = new
            {
                pendingCount,
                approvedFutureCount,
                activeTodayCount,
                completedTodayCount,
                cancelledTodayCount
            },
            pendingReservations = pendingPreview.Select(r => new
            {
                id = r.Id,
                nic = r.NIC,
                prosumerName = LookupName(nameByNic, r.NIC),
                nodeId = r.NodeId,
                stationName = r.StationName,
                slotStartTime = r.SlotStartTime,
                slotEndTime = r.SlotEndTime,
                energyKWh = r.EnergyKWh,
                status = r.Status
            }),
            todayBookings = todayPreview.Select(r => new
            {
                id = r.Id,
                nic = r.NIC,
                prosumerName = LookupName(nameByNic, r.NIC),
                nodeId = r.NodeId,
                stationName = r.StationName,
                slotStartTime = r.SlotStartTime,
                slotEndTime = r.SlotEndTime,
                energyKWh = r.EnergyKWh,
                status = r.Status,

                // Return only a flag, not the QR token itself
                qrIssued = !string.IsNullOrEmpty(r.QrToken) && r.QrUsedAt == null
            })
        });
    }

    // GET: api/operator/stations
    // Returns active stations for the dashboard node filter.
    [HttpGet("stations")]
    public async Task<IActionResult> GetOperatorStations()
    {
        var stations = await _nodes.Find(n => n.IsActive).ToListAsync();

        return Ok(stations.Select(n => new
        {
            id = n.Id,
            stationName = n.StationName,
            latitude = n.Latitude,
            longitude = n.Longitude,
            availableBatterySlots = n.AvailableBatterySlots,
            totalBatterySlots = n.TotalBatterySlots
        }));
    }

    // Builds a NIC to full name lookup for the prosumers in the preview lists.
    private async Task<Dictionary<string, string>> BuildProsumerNameLookupAsync(
        List<EnergyReservation> pendingPreview,
        List<EnergyReservation> todayPreview)
    {
        var nics = pendingPreview
            .Select(r => r.NIC)
            .Concat(todayPreview.Select(r => r.NIC))
            .Where(nic => !string.IsNullOrWhiteSpace(nic))
            .Distinct()
            .ToList();

        if (nics.Count == 0)
        {
            return new Dictionary<string, string>();
        }

        var users = await _users
            .Find(Builders<User>.Filter.In(u => u.NIC, nics))
            .ToListAsync();

        var lookup = new Dictionary<string, string>();

        foreach (var user in users)
        {
            lookup[user.NIC] = user.FullName;
        }

        return lookup;
    }

    // Returns the prosumer's name, or the NIC if no name is found.
    private static string LookupName(Dictionary<string, string> nameByNic, string nic)
    {
        return nameByNic.TryGetValue(nic, out var fullName) && !string.IsNullOrWhiteSpace(fullName)
            ? fullName
            : nic;
    }
}
