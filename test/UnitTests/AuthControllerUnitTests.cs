using Microsoft.AspNetCore.Mvc;
using SolarMicrogrid.Api.Controllers;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using SolarMicrogrid.Tests.TestHelpers;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class AuthControllerUnitTests
{
    private readonly InMemoryMongoStore _store;
    private readonly AuthController _controller;

    public AuthControllerUnitTests()
    {
        _store = new InMemoryMongoStore();
        var config = TestAuthHelper.CreateTestConfiguration();
        _controller = new AuthController(_store.Client, config);
    }

    [Theory]
    [InlineData("", "password123")]
    [InlineData("200012345678", "")]
    [InlineData("   ", "   ")]
    public async Task Login_WithMissingNicOrPassword_ReturnsBadRequest(string nic, string password)
    {
        var result = await _controller.Login(new LoginDto { NIC = nic, Password = password });

        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task Login_WhenUserDoesNotExist_ReturnsUnauthorized()
    {
        var result = await _controller.Login(new LoginDto
        {
            NIC = "999999999V",
            Password = "password123"
        });

        Assert.IsType<UnauthorizedObjectResult>(result);
    }

    [Fact]
    public async Task Login_WithWrongPassword_ReturnsUnauthorized()
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439011",
            NIC = "200012345678",
            FullName = "Test User",
            PasswordHash = BCrypt.Net.BCrypt.HashPassword("CorrectPassword!"),
            Role = "Backoffice",
            Status = "Active"
        });

        var result = await _controller.Login(new LoginDto
        {
            NIC = "200012345678",
            Password = "WrongPassword!"
        });

        Assert.IsType<UnauthorizedObjectResult>(result);
    }

    [Theory]
    [InlineData("Pending")]
    [InlineData("Deactivated")]
    public async Task Login_WhenUserIsNotActive_ReturnsUnauthorized(string status)
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439012",
            NIC = "200012345679",
            FullName = "Inactive User",
            PasswordHash = BCrypt.Net.BCrypt.HashPassword("Secret123!"),
            Role = "Prosumer",
            Status = status
        });

        var result = await _controller.Login(new LoginDto
        {
            NIC = "200012345679",
            Password = "Secret123!"
        });

        Assert.IsType<UnauthorizedObjectResult>(result);
    }

    [Fact]
    public async Task Login_WithValidActiveUser_ReturnsOkWithJwtToken()
    {
        _store.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439013",
            NIC = "200012345680",
            FullName = "Active Admin",
            PasswordHash = BCrypt.Net.BCrypt.HashPassword("Secret123!"),
            Role = "Backoffice",
            Status = "Active"
        });

        var result = await _controller.Login(new LoginDto
        {
            NIC = "200012345680",
            Password = "Secret123!"
        });

        var okResult = Assert.IsType<OkObjectResult>(result);
        Assert.NotNull(okResult.Value);

        var tokenProp = okResult.Value.GetType().GetProperty("token");
        Assert.NotNull(tokenProp);
        var tokenValue = tokenProp.GetValue(okResult.Value) as string;
        Assert.False(string.IsNullOrWhiteSpace(tokenValue));
    }
}
