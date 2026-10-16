using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging.Abstractions;
using SolarMicrogrid.Api.Controllers;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Tests.TestHelpers;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class ReservationsControllerUnitTests
{
    private readonly InMemoryMongoStore _store;
    private readonly ReservationsController _controller;

    public ReservationsControllerUnitTests()
    {
        _store = new InMemoryMongoStore();
        var config = TestAuthHelper.CreateTestConfiguration();
        _controller = new ReservationsController(
            _store.Client,
            config,
            NullLogger<ReservationsController>.Instance);
    }

    [Fact]
    public async Task CreateReservation_WhenProsumerCreatesForAnotherNic_ReturnsForbid()
    {
        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.CreateReservation(new CreateReservationDto
        {
            ProsumerNic = "200299998888",
            NodeId = "507f1f77bcf86cd799439051",
            EnergyKWh = 20,
            SlotStartTime = DateTime.UtcNow.AddDays(2),
            SlotEndTime = DateTime.UtcNow.AddDays(2).AddHours(2)
        });

        Assert.IsType<ForbidResult>(result);
    }

    [Fact]
    public async Task CreateReservation_WhenSlotEndTimeIsBeforeStartTime_ReturnsBadRequest()
    {
        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");
        var start = DateTime.UtcNow.AddDays(2);

        var result = await _controller.CreateReservation(new CreateReservationDto
        {
            ProsumerNic = "200200001111",
            NodeId = "507f1f77bcf86cd799439051",
            EnergyKWh = 20,
            SlotStartTime = start,
            SlotEndTime = start.AddHours(-1)
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task CreateReservation_WhenNodeIsInactive_ReturnsBadRequest()
    {
        var nodeId = "507f1f77bcf86cd799439051";
        _store.Seed("SolarStationInfo", new SolarStationInfo
        {
            Id = nodeId,
            StationName = "Offline Hub",
            IsActive = false,
            CapacityKWh = 100,
            TotalBatterySlots = 4
        });

        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");
        var start = DateTime.UtcNow.AddDays(2);

        var result = await _controller.CreateReservation(new CreateReservationDto
        {
            ProsumerNic = "200200001111",
            NodeId = nodeId,
            EnergyKWh = 20,
            SlotStartTime = start,
            SlotEndTime = start.AddHours(2)
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task CreateReservation_WithValidInput_CreatesPendingReservationAndUpdatesSlotCapacity()
    {
        var nodeId = "507f1f77bcf86cd799439052";
        _store.Seed("SolarStationInfo", new SolarStationInfo
        {
            Id = nodeId,
            StationName = "Negombo Solar Hub",
            IsActive = true,
            CapacityKWh = 100.0,
            TotalBatterySlots = 4,
            AvailableBatterySlots = 4
        });

        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");
        var start = DateTime.UtcNow.AddDays(2);

        var result = await _controller.CreateReservation(new CreateReservationDto
        {
            ProsumerNic = "200200001111",
            NodeId = nodeId,
            EnergyKWh = 30.0,
            SlotStartTime = start,
            SlotEndTime = start.AddHours(2)
        });

        var created = Assert.IsType<CreatedAtActionResult>(result);
        var dto = Assert.IsType<ReservationResponseDto>(created.Value);
        Assert.Equal("Pending", dto.Status);
        Assert.Equal(30.0, dto.EnergyKWh);

        var slotDoc = Assert.Single(_store.GetRawCollection("EnergyBookingSlots"));
        Assert.Equal(70.0, slotDoc["availableCapacityKWh"].ToDouble());
        Assert.Equal(3, slotDoc["availableSlotCount"].ToInt32());
    }

    [Fact]
    public async Task ApproveReservation_WhenPending_TransitionsToApproved()
    {
        var resId = "507f1f77bcf86cd799439053";
        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            NodeId = "507f1f77bcf86cd799439052",
            StationName = "Negombo Solar Hub",
            EnergyKWh = 25.0,
            SlotStartTime = DateTime.UtcNow.AddDays(2),
            SlotEndTime = DateTime.UtcNow.AddDays(2).AddHours(2),
            Status = ReservationStatus.Pending
        });

        TestAuthHelper.AttachUserContext(_controller, "operator01", "GridOperator");

        var result = await _controller.ApproveReservation(resId);

        Assert.IsType<OkObjectResult>(result);
        var resDoc = Assert.Single(_store.GetRawCollection("EnergyReservation"));
        Assert.Equal("Approved", resDoc["status"].AsString);
    }

    [Fact]
    public async Task CancelReservation_WhenLessThan12HoursNotice_ReturnsBadRequest()
    {
        var resId = "507f1f77bcf86cd799439054";
        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            NodeId = "507f1f77bcf86cd799439052",
            EnergyKWh = 20.0,
            SlotStartTime = DateTime.UtcNow.AddHours(6), // Less than 12h notice
            SlotEndTime = DateTime.UtcNow.AddHours(8),
            Status = ReservationStatus.Pending
        });

        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.CancelReservation(resId);

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task CancelReservation_WhenMoreThan12HoursNotice_CancelsAndRestoresSlotCapacity()
    {
        var slotId = "507f1f77bcf86cd799439055";
        var resId = "507f1f77bcf86cd799439056";
        var start = DateTime.UtcNow.AddDays(2);

        _store.Seed("EnergyBookingSlots", new EnergyBookingSlot
        {
            Id = slotId,
            NodeId = "507f1f77bcf86cd799439052",
            StationName = "Negombo Solar Hub",
            SlotStartTime = start,
            SlotEndTime = start.AddHours(2),
            TotalCapacityKWh = 100.0,
            AvailableCapacityKWh = 75.0,
            TotalSlotCount = 4,
            AvailableSlotCount = 3,
            Status = "Open",
            ReservationIds = new List<string> { resId }
        });

        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            NodeId = "507f1f77bcf86cd799439052",
            SlotId = slotId,
            EnergyKWh = 25.0,
            SlotStartTime = start,
            SlotEndTime = start.AddHours(2),
            Status = ReservationStatus.Approved
        });

        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.CancelReservation(resId);

        Assert.IsType<OkObjectResult>(result);
        var resDoc = Assert.Single(_store.GetRawCollection("EnergyReservation"));
        Assert.Equal("Cancelled", resDoc["status"].AsString);

        var slotDoc = Assert.Single(_store.GetRawCollection("EnergyBookingSlots"));
        Assert.Equal(100.0, slotDoc["availableCapacityKWh"].ToDouble());
        Assert.Equal(4, slotDoc["availableSlotCount"].ToInt32());
    }
}
