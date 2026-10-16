using Microsoft.AspNetCore.Mvc;
using SolarMicrogrid.Api.Controllers;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Tests.TestHelpers;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class SlotsAndOperatorUnitTests
{
    private readonly InMemoryMongoStore _store;

    public SlotsAndOperatorUnitTests()
    {
        _store = new InMemoryMongoStore();
    }

    [Fact]
    public async Task SlotsController_UpdateSlotStatus_ClampsSlotCountAndCapacity()
    {
        var config = TestAuthHelper.CreateTestConfiguration();
        var controller = new SlotsController(_store.Client, config);
        TestAuthHelper.AttachUserContext(controller, "operator01", "GridOperator");

        var slotId = "507f1f77bcf86cd799439071";
        _store.Seed("EnergyBookingSlots", new EnergyBookingSlot
        {
            Id = slotId,
            NodeId = "507f1f77bcf86cd799439052",
            StationName = "Negombo Hub",
            TotalSlotCount = 4,
            AvailableSlotCount = 2,
            TotalCapacityKWh = 100.0,
            AvailableCapacityKWh = 50.0,
            Status = "Open"
        });

        var result = await controller.UpdateSlotStatus(slotId, new UpdateSlotStatusRequest
        {
            Status = "Closed",
            AvailableSlotCount = 10, // Exceeds TotalSlotCount (4), should clamp to 4
            AvailableCapacityKWh = 250.0 // Exceeds TotalCapacityKWh (100), should clamp to 100
        });

        var okResult = Assert.IsType<OkObjectResult>(result);
        var updatedSlot = Assert.IsType<EnergyBookingSlot>(okResult.Value);
        Assert.Equal("Closed", updatedSlot.Status);
        Assert.Equal(4, updatedSlot.AvailableSlotCount);
        Assert.Equal(100.0, updatedSlot.AvailableCapacityKWh);
    }

    [Fact]
    public async Task OperatorController_GetOperatorDashboard_WithInvalidNodeId_ReturnsBadRequest()
    {
        var config = TestAuthHelper.CreateTestConfiguration();
        var controller = new OperatorController(_store.Client, config);
        TestAuthHelper.AttachUserContext(controller, "operator01", "GridOperator");

        var result = await controller.GetOperatorDashboard("invalid-node-id");

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task OperatorController_GetOperatorDashboard_ReturnsCountsAndPreviews()
    {
        var config = TestAuthHelper.CreateTestConfiguration();
        var controller = new OperatorController(_store.Client, config);
        TestAuthHelper.AttachUserContext(controller, "operator01", "GridOperator");

        var nodeId = "507f1f77bcf86cd799439072";
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439073",
            NIC = "200200001111",
            FullName = "Kasun Gunathilaka",
            Role = "Prosumer",
            Status = "Active"
        });

        _store.Seed("EnergyReservation",
            new EnergyReservation
            {
                Id = "507f1f77bcf86cd799439074",
                ProsumerNic = "200200001111",
                NodeId = nodeId,
                StationName = "Kandy Hub",
                EnergyKWh = 15.0,
                SlotStartTime = DateTime.UtcNow.AddDays(1),
                SlotEndTime = DateTime.UtcNow.AddDays(1).AddHours(2),
                Status = ReservationStatus.Pending
            });

        var result = await controller.GetOperatorDashboard(nodeId);

        Assert.IsType<OkObjectResult>(result);
    }

    [Fact]
    public void TestController_TestConnection_ReturnsOkWhenDatabaseReachable()
    {
        var config = TestAuthHelper.CreateTestConfiguration();
        var controller = new TestController(_store.Client, config);

        var result = controller.TestConnection();

        Assert.IsType<OkObjectResult>(result);
    }
}
