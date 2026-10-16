using Microsoft.AspNetCore.Mvc;
using SolarMicrogrid.Api.Controllers;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Tests.TestHelpers;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class UsersControllerUnitTests
{
    private readonly InMemoryMongoStore _store;
    private readonly UsersController _controller;

    public UsersControllerUnitTests()
    {
        _store = new InMemoryMongoStore();
        var config = TestAuthHelper.CreateTestConfiguration();
        _controller = new UsersController(_store.Client, config);
        TestAuthHelper.AttachUserContext(_controller, "admin001", "Backoffice");
    }

    [Fact]
    public async Task CreateBackofficeUser_WithMissingRequiredFields_ReturnsBadRequest()
    {
        var dto = new CreateBackofficeUserDto
        {
            NIC = "",
            FullName = "Backoffice Admin",
            Password = "Password123"
        };

        var result = await _controller.CreateBackofficeUser(dto);

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task CreateBackofficeUser_WithDuplicateNic_ReturnsConflict()
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439021",
            NIC = "199912345678",
            FullName = "Existing User",
            Role = "Backoffice",
            Status = "Active"
        });

        var dto = new CreateBackofficeUserDto
        {
            NIC = "199912345678",
            FullName = "Duplicate User",
            Password = "Password123"
        };

        var result = await _controller.CreateBackofficeUser(dto);

        Assert.IsType<ConflictObjectResult>(result);
    }

    [Fact]
    public async Task CreateBackofficeUser_WithValidDto_CreatesActiveBackofficeUser()
    {
        var dto = new CreateBackofficeUserDto
        {
            NIC = "199988887777",
            FullName = "New Admin",
            Email = "admin@gmail.com",
            Phone = "0771234567",
            Password = "Password123"
        };

        var result = await _controller.CreateBackofficeUser(dto);

        Assert.IsType<CreatedResult>(result);
        Assert.Single(_store.GetRawCollection("Users"));
    }

    [Theory]
    [InlineData("12345", "admin@gmail.com", "0771234567")]
    [InlineData("199988887777", "admin@yahoo.com", "0771234567")]
    [InlineData("199988887777", "admin@gmail.com", "7712345678")]
    [InlineData("199988887777", "admin@gmail.com", "07712345")]
    public async Task CreateBackofficeUser_WithInvalidFormat_ReturnsBadRequest(string nic, string email, string phone)
    {
        var dto = new CreateBackofficeUserDto
        {
            NIC = nic,
            FullName = "New Admin",
            Email = email,
            Phone = phone,
            Password = "Password123"
        };

        var result = await _controller.CreateBackofficeUser(dto);

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task CreateGridOperatorUser_WithValidDto_CreatesActiveGridOperator()
    {
        var dto = new CreateGridOperatorDto
        {
            NIC = "199877776666",
            FullName = "New Operator",
            Email = "operator@gmail.com",
            Phone = "0711234567",
            Password = "Password123"
        };

        var result = await _controller.CreateGridOperatorUser(dto);

        Assert.IsType<CreatedResult>(result);
        var doc = Assert.Single(_store.GetRawCollection("Users"));
        Assert.Equal("GridOperator", doc["role"].AsString);
        Assert.Equal("Active", doc["status"].AsString);
    }

    [Fact]
    public async Task GetUserById_WhenNotFound_ReturnsNotFound()
    {
        var result = await _controller.GetUserById("507f1f77bcf86cd799439099");

        Assert.IsType<NotFoundObjectResult>(result);
    }

    [Fact]
    public async Task UpdateUser_WithInvalidStatus_ReturnsBadRequest()
    {
        var userId = "507f1f77bcf86cd799439022";
        _store.Seed("Users", new User
        {
            Id = userId,
            NIC = "200111222333",
            FullName = "Operator One",
            Role = "GridOperator",
            Status = "Active"
        });

        var result = await _controller.UpdateUser(userId, new UpdateUserDto
        {
            FullName = "Operator Updated",
            Status = "Banned" // Not in allowed statuses
        });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task UpdateUser_WithValidStatus_UpdatesAndReturnsOk()
    {
        var userId = "507f1f77bcf86cd799439023";
        _store.Seed("Users", new User
        {
            Id = userId,
            NIC = "200111222334",
            FullName = "Operator One",
            Role = "GridOperator",
            Status = "Active"
        });

        var result = await _controller.UpdateUser(userId, new UpdateUserDto
        {
            FullName = "Operator Updated",
            Email = "updated@gmail.com",
            Phone = "0770001111",
            Address = "Colombo",
            Status = "Deactivated"
        });

        Assert.IsType<OkObjectResult>(result);
        var doc = Assert.Single(_store.GetRawCollection("Users"));
        Assert.Equal("Operator Updated", doc["fullName"].AsString);
        Assert.Equal("Deactivated", doc["status"].AsString);
    }

    [Fact]
    public async Task DeactivateUser_WhenUserExists_SoftDeletesUser()
    {
        var userId = "507f1f77bcf86cd799439024";
        _store.Seed("Users", new User
        {
            Id = userId,
            NIC = "200111222335",
            FullName = "Active User",
            Role = "GridOperator",
            Status = "Active"
        });

        var result = await _controller.DeactivateUser(userId);

        Assert.IsType<OkObjectResult>(result);
        var doc = Assert.Single(_store.GetRawCollection("Users"));
        Assert.Equal("Deactivated", doc["status"].AsString);
    }
}
