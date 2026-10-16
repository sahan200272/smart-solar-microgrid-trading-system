using Microsoft.AspNetCore.Mvc;
using MongoDB.Bson;
using SolarMicrogrid.Api.Controllers;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Tests.TestHelpers;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class NodesControllerUnitTests
{
    private readonly InMemoryMongoStore _store;
    private readonly NodesController _controller;

    public NodesControllerUnitTests()
    {
        _store = new InMemoryMongoStore();
        var config = TestAuthHelper.CreateTestConfiguration();
        _controller = new NodesController(_store.Client, config);
        TestAuthHelper.AttachUserContext(_controller, "admin01", "Backoffice");
    }

    [Fact]
    public async Task CreateNode_WithEmptyStationName_ReturnsBadRequest()
    {
        var result = await _controller.CreateNode(new CreateNodeDto
        {
            StationName = "   ",
            TotalBatterySlots = 4,
            CapacityKWh = 100
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task CreateNode_WithZeroOrNegativeBatterySlots_ReturnsBadRequest()
    {
        var result = await _controller.CreateNode(new CreateNodeDto
        {
            StationName = "Negombo Hub",
            TotalBatterySlots = 0,
            CapacityKWh = 100
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task CreateNode_WithValidDto_ReturnsCreatedAtActionAndSetsAvailableSlots()
    {
        var result = await _controller.CreateNode(new CreateNodeDto
        {
            StationName = "Negombo Beach Hub",
            Latitude = 7.2083,
            Longitude = 79.8358,
            CapacityKWh = 150.0,
            TotalBatterySlots = 6,
            OperatingSchedule = "06:00 - 22:00"
        });

        var created = Assert.IsType<CreatedAtActionResult>(result);
        var node = Assert.IsType<SolarStationInfo>(created.Value);
        Assert.Equal("Negombo Beach Hub", node.StationName);
        Assert.Equal(6, node.AvailableBatterySlots);
        Assert.True(node.IsActive);
    }

    [Fact]
    public async Task GetNearbyNodes_FiltersNodesByHaversineDistanceAndActiveStatus()
    {
        _store.Seed("SolarStationInfo",
            new SolarStationInfo
            {
                Id = "507f1f77bcf86cd799439041",
                StationName = "Negombo Hub (Nearby)",
                Latitude = 7.2083,
                Longitude = 79.8358,
                IsActive = true
            },
            new SolarStationInfo
            {
                Id = "507f1f77bcf86cd799439042",
                StationName = "Galle Hub (Far Away)",
                Latitude = 6.0535,
                Longitude = 80.2210,
                IsActive = true
            },
            new SolarStationInfo
            {
                Id = "507f1f77bcf86cd799439043",
                StationName = "Inactive Negombo Hub",
                Latitude = 7.2090,
                Longitude = 79.8360,
                IsActive = false
            });

        var result = await _controller.GetNearbyNodes(7.2083, 79.8358, radiusKm: 10);

        var okResult = Assert.IsType<OkObjectResult>(result);
        var nearby = Assert.IsAssignableFrom<List<SolarStationInfo>>(okResult.Value);
        var single = Assert.Single(nearby);
        Assert.Equal("Negombo Hub (Nearby)", single.StationName);
    }

    [Fact]
    public async Task UpdateNode_AdjustsAndClampsAvailableBatterySlotsProportionally()
    {
        var nodeId = "507f1f77bcf86cd799439044";
        _store.Seed("SolarStationInfo", new SolarStationInfo
        {
            Id = nodeId,
            StationName = "Colombo Hub",
            TotalBatterySlots = 6,
            AvailableBatterySlots = 2, // 4 slots in use
            CapacityKWh = 100,
            IsActive = true
        });

        // Reduce total slots from 6 to 3 -> difference is -3 -> 2 + (-3) = -1 -> clamped to 0
        var result = await _controller.UpdateNode(nodeId, new UpdateNodeDto
        {
            StationName = "Colombo Central Hub",
            Latitude = 6.9271,
            Longitude = 79.8612,
            CapacityKWh = 120,
            TotalBatterySlots = 3,
            OperatingSchedule = "08:00 - 20:00"
        });

        var okResult = Assert.IsType<OkObjectResult>(result);
        var updated = Assert.IsType<SolarStationInfo>(okResult.Value);
        Assert.Equal("Colombo Central Hub", updated.StationName);
        Assert.Equal(3, updated.TotalBatterySlots);
        Assert.Equal(0, updated.AvailableBatterySlots);
    }

    [Fact]
    public async Task DeactivateNode_WhenActiveReservationsExist_ReturnsBadRequest()
    {
        var nodeId = "507f1f77bcf86cd799439045";
        _store.Seed("SolarStationInfo", new SolarStationInfo
        {
            Id = nodeId,
            StationName = "Busy Hub",
            TotalBatterySlots = 4,
            AvailableBatterySlots = 3,
            IsActive = true
        });

        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = "507f1f77bcf86cd799439046",
            NodeId = nodeId,
            ProsumerNic = "200212345678",
            Status = ReservationStatus.Approved
        });

        var result = await _controller.DeactivateNode(nodeId);

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task DeactivateNode_WhenNoActiveReservationsExist_DeactivatesSuccessfully()
    {
        var nodeId = "507f1f77bcf86cd799439047";
        _store.Seed("SolarStationInfo", new SolarStationInfo
        {
            Id = nodeId,
            StationName = "Idle Hub",
            TotalBatterySlots = 4,
            AvailableBatterySlots = 4,
            IsActive = true
        });

        var result = await _controller.DeactivateNode(nodeId);

        Assert.IsType<OkObjectResult>(result);
        var nodeDoc = Assert.Single(_store.GetRawCollection("SolarStationInfo"));
        Assert.False(nodeDoc["isActive"].AsBoolean);
    }
}
