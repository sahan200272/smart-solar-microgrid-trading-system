using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;
using Microsoft.IdentityModel.Tokens;

namespace SolarMicrogrid.Tests.TestHelpers;

public static class TestAuthHelper
{
    public const string TestJwtKey = "SuperSecretTestJwtKeyForSolarMicrogridSystem2026!";
    public const string TestJwtIssuer = "SolarMicrogridTestIssuer";
    public const string TestJwtAudience = "SolarMicrogridTestAudience";
    public const string TestDatabaseName = "SolarMicrogridTestDB";

    public static IConfiguration CreateTestConfiguration()
    {
        var settings = new Dictionary<string, string?>
        {
            ["MongoDbSettings:ConnectionString"] = "mongodb://localhost:27017",
            ["MongoDbSettings:DatabaseName"] = TestDatabaseName,
            ["JwtSettings:Key"] = TestJwtKey,
            ["JwtSettings:Issuer"] = TestJwtIssuer,
            ["JwtSettings:Audience"] = TestJwtAudience,
            ["JwtSettings:ExpiryInMinutes"] = "120"
        };

        return new ConfigurationBuilder()
            .AddInMemoryCollection(settings)
            .Build();
    }

    public static void AttachUserContext(ControllerBase controller, string nic, string role, string? userId = null)
    {
        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier, userId ?? "507f1f77bcf86cd799439011"),
            new(ClaimTypes.Name, nic),
            new(ClaimTypes.Role, role)
        };

        var identity = new ClaimsIdentity(claims, "TestAuthType");
        var principal = new ClaimsPrincipal(identity);

        controller.ControllerContext = new ControllerContext
        {
            HttpContext = new DefaultHttpContext
            {
                User = principal
            }
        };
    }

    public static string GenerateTestJwtToken(string nic, string role, string? userId = null)
    {
        var claims = new[]
        {
            new Claim(ClaimTypes.NameIdentifier, userId ?? "507f1f77bcf86cd799439011"),
            new Claim(ClaimTypes.Name, nic),
            new Claim(ClaimTypes.Role, role)
        };

        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(TestJwtKey));
        var credentials = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);

        var token = new JwtSecurityToken(
            issuer: TestJwtIssuer,
            audience: TestJwtAudience,
            claims: claims,
            expires: DateTime.UtcNow.AddHours(2),
            signingCredentials: credentials
        );

        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}
