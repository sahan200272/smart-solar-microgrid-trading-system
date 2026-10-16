using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using SolarMicrogrid.Api.Controllers;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Tests.TestHelpers;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class ReservationQrControllerUnitTests
{
    private readonly InMemoryMongoStore _store;
    private readonly ReservationQrController _controller;

    public ReservationQrControllerUnitTests()
    {
        _store = new InMemoryMongoStore();
        var config = TestAuthHelper.CreateTestConfiguration();
        _controller = new ReservationQrController(_store.Client, config);
    }

    [Fact]
    public async Task GenerateQr_WithInvalidObjectId_ReturnsBadRequest()
    {
        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.GenerateQr("not-an-object-id");

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task GenerateQr_WhenProsumerDoesNotOwnReservation_Returns403Forbidden()
    {
        var resId = "507f1f77bcf86cd799439061";
        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200299998888",
            Status = ReservationStatus.Approved
        });

        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.GenerateQr(resId);

        var statusResult = Assert.IsType<ObjectResult>(result);
        Assert.Equal(StatusCodes.Status403Forbidden, statusResult.StatusCode);
    }

    [Fact]
    public async Task GenerateQr_WhenReservationIsNotApproved_ReturnsConflict()
    {
        var resId = "507f1f77bcf86cd799439062";
        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            Status = ReservationStatus.Pending
        });

        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.GenerateQr(resId);

        Assert.IsType<ConflictObjectResult>(result);
    }

    [Fact]
    public async Task GenerateQr_WhenApprovedAndFutureSlot_ReturnsOkWithQrPayload()
    {
        var resId = "507f1f77bcf86cd799439063";
        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            NodeId = "507f1f77bcf86cd799439052",
            StationName = "Negombo Hub",
            EnergyKWh = 15.0,
            SlotStartTime = DateTime.UtcNow.AddMinutes(10),
            SlotEndTime = DateTime.UtcNow.AddHours(2),
            Status = ReservationStatus.Approved
        });

        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.GenerateQr(resId);

        var okResult = Assert.IsType<OkObjectResult>(result);
        Assert.NotNull(okResult.Value);
        var payloadProp = okResult.Value.GetType().GetProperty("qrPayload");
        var payload = payloadProp?.GetValue(okResult.Value) as string;
        Assert.NotNull(payload);
        Assert.StartsWith("SSMG:1:", payload);
    }

    [Fact]
    public async Task VerifyQr_WithMalformedToken_ReturnsBadRequest()
    {
        TestAuthHelper.AttachUserContext(_controller, "operator01", "GridOperator");

        var result = await _controller.VerifyQr(new VerifyQrDto
        {
            QrToken = "SSMG:1:invalid$token@with#special!chars"
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task VerifyQr_WithValidTokenAndActiveProsumer_ReturnsOk()
    {
        var resId = "507f1f77bcf86cd799439064";
        var nodeId = "507f1f77bcf86cd799439065";
        var token = "ValidBase64UrlToken1234567890";

        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439066",
            NIC = "200200001111",
            FullName = "Active Prosumer",
            Phone = "0771234567",
            Role = "Prosumer",
            Status = "Active"
        });

        _store.Seed("SolarStationInfo", new SolarStationInfo
        {
            Id = nodeId,
            StationName = "Negombo Hub",
            IsActive = true
        });

        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            NodeId = nodeId,
            StationName = "Negombo Hub",
            EnergyKWh = 20.0,
            SlotStartTime = DateTime.UtcNow.AddMinutes(10),
            SlotEndTime = DateTime.UtcNow.AddHours(2),
            Status = ReservationStatus.Approved,
            QrToken = token,
            QrExpiresAt = DateTime.UtcNow.AddHours(2)
        });

        TestAuthHelper.AttachUserContext(_controller, "operator01", "GridOperator");

        var result = await _controller.VerifyQr(new VerifyQrDto
        {
            QrToken = "SSMG:1:" + token,
            NodeId = nodeId
        });

        Assert.IsType<OkObjectResult>(result);
    }

    [Fact]
    public async Task FinalizeReservation_WhenDeliveredEnergyExceeds10PercentBuffer_ReturnsBadRequest()
    {
        var resId = "507f1f77bcf86cd799439067";
        var nodeId = "507f1f77bcf86cd799439065";
        var token = "ValidFinalizeToken123456";

        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439066",
            NIC = "200200001111",
            FullName = "Active Prosumer",
            Role = "Prosumer",
            Status = "Active"
        });

        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            NodeId = nodeId,
            EnergyKWh = 20.0,
            SlotStartTime = DateTime.UtcNow.AddMinutes(5),
            SlotEndTime = DateTime.UtcNow.AddHours(2),
            Status = ReservationStatus.Approved,
            QrToken = token,
            QrExpiresAt = DateTime.UtcNow.AddHours(2)
        });

        TestAuthHelper.AttachUserContext(_controller, "operator01", "GridOperator");

        // 20 kWh + 10% = 22 kWh max; 25 kWh exceeds limit
        var result = await _controller.FinalizeReservation(resId, new FinalizeReservationDto
        {
            QrToken = token,
            NodeId = nodeId,
            DeliveredKWh = 25.0
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task FinalizeReservation_WithValidTokenAndDeliveredEnergy_MarksCompleted()
    {
        var resId = "507f1f77bcf86cd799439068";
        var nodeId = "507f1f77bcf86cd799439065";
        var token = "ValidFinalizeToken999999";

        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439066",
            NIC = "200200001111",
            FullName = "Active Prosumer",
            Role = "Prosumer",
            Status = "Active"
        });

        _store.Seed("EnergyReservation", new EnergyReservation
        {
            Id = resId,
            ProsumerNic = "200200001111",
            NodeId = nodeId,
            EnergyKWh = 20.0,
            SlotStartTime = DateTime.UtcNow.AddMinutes(5),
            SlotEndTime = DateTime.UtcNow.AddHours(2),
            Status = ReservationStatus.Approved,
            QrToken = token,
            QrExpiresAt = DateTime.UtcNow.AddHours(2)
        });

        TestAuthHelper.AttachUserContext(_controller, "operator01", "GridOperator", "507f1f77bcf86cd799439090");

        var result = await _controller.FinalizeReservation(resId, new FinalizeReservationDto
        {
            QrToken = "SSMG:1:" + token,
            NodeId = nodeId,
            DeliveredKWh = 21.0
        });

        Assert.IsType<OkObjectResult>(result);
        var doc = Assert.Single(_store.GetRawCollection("EnergyReservation"));
        Assert.Equal("Completed", doc["status"].AsString);
        Assert.Equal(21.0, doc["deliveredKWh"].ToDouble());
    }
}
