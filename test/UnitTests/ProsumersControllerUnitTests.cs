using Microsoft.AspNetCore.Mvc;
using SolarMicrogrid.Api.Controllers;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Tests.TestHelpers;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class ProsumersControllerUnitTests
{
    private readonly InMemoryMongoStore _store;
    private readonly ProsumersController _controller;

    public ProsumersControllerUnitTests()
    {
        _store = new InMemoryMongoStore();
        var config = TestAuthHelper.CreateTestConfiguration();
        _controller = new ProsumersController(_store.Client, config);
    }

    [Fact]
    public async Task RegisterProsumer_WithMissingRequiredFields_ReturnsBadRequest()
    {
        var result = await _controller.RegisterProsumer(new RegisterProsumerDto
        {
            NIC = "200212345678",
            FullName = "",
            Password = "Password123"
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task RegisterProsumer_WithValidDto_CreatesPendingProsumer()
    {
        var result = await _controller.RegisterProsumer(new RegisterProsumerDto
        {
            NIC = "200212345678",
            FullName = "Sahan Perera",
            Email = "sahan@example.com",
            Phone = "0771234567",
            Address = "Negombo",
            Password = "Password123!"
        });

        Assert.IsType<CreatedResult>(result);
        var saved = Assert.Single(_store.GetRawCollection("Users"));
        Assert.Equal("Prosumer", saved["role"].AsString);
        Assert.Equal("Pending", saved["status"].AsString);
    }

    [Fact]
    public async Task ActivateProsumer_WhenPending_SetsStatusToActive()
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439031",
            NIC = "200212345678",
            FullName = "Pending Prosumer",
            Role = "Prosumer",
            Status = "Pending"
        });
        TestAuthHelper.AttachUserContext(_controller, "admin01", "Backoffice");

        var result = await _controller.ActivateProsumer("200212345678");

        Assert.IsType<OkObjectResult>(result);
        var doc = Assert.Single(_store.GetRawCollection("Users"));
        Assert.Equal("Active", doc["status"].AsString);
    }

    [Fact]
    public async Task ActivateProsumer_WhenAlreadyActive_ReturnsBadRequest()
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439032",
            NIC = "200212345678",
            FullName = "Active Prosumer",
            Role = "Prosumer",
            Status = "Active"
        });
        TestAuthHelper.AttachUserContext(_controller, "admin01", "Backoffice");

        var result = await _controller.ActivateProsumer("200212345678");

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task GetProsumerByNIC_WhenProsumerRequestsAnotherNic_ReturnsForbid()
    {
        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.GetProsumerByNIC("200299998888");

        Assert.IsType<ForbidResult>(result);
    }

    [Fact]
    public async Task UpdateProsumer_WhenAccountIsPending_ReturnsBadRequest()
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439033",
            NIC = "200200001111",
            FullName = "Pending Prosumer",
            Role = "Prosumer",
            Status = "Pending"
        });
        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");

        var result = await _controller.UpdateProsumer("200200001111", new UpdateProsumerDto
        {
            FullName = "Updated Name"
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task DeactivateAndReactivateProsumer_Lifecycle_Succeeds()
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439034",
            NIC = "200200001111",
            FullName = "Active Prosumer",
            Role = "Prosumer",
            Status = "Active"
        });

        // Prosumer deactivates own account
        TestAuthHelper.AttachUserContext(_controller, "200200001111", "Prosumer");
        var deactivateResult = await _controller.DeactivateProsumer("200200001111");
        Assert.IsType<OkObjectResult>(deactivateResult);
        Assert.Equal("Deactivated", _store.GetRawCollection("Users")[0]["status"].AsString);

        // Backoffice reactivates account
        TestAuthHelper.AttachUserContext(_controller, "admin01", "Backoffice");
        var reactivateResult = await _controller.ReactivateProsumer("200200001111");
        Assert.IsType<OkObjectResult>(reactivateResult);
        Assert.Equal("Active", _store.GetRawCollection("Users")[0]["status"].AsString);
    }
}
