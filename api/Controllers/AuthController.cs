// File: AuthController.cs
// Component: User & Access Management
// Description: Handles authentication and JWT token generation.
// POST  /api/auth/login

using Microsoft.AspNetCore.Mvc;
using Microsoft.IdentityModel.Tokens;
using MongoDB.Driver;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/auth")]
public class AuthController : ControllerBase
{
    // MongoDB collection used to store and retrieve user accounts.
    private readonly IMongoCollection<User> _users;

    // Application configuration used to access MongoDB and JWT settings.
    private readonly IConfiguration _configuration;

    public AuthController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);

        _users = database.GetCollection<User>("Users");
        _configuration = configuration;
    }

// POST: api/auth/login
// Verifies the user's credentials and returns a JWT token if valid with the user's role.
    [HttpPost("login")]
    public async Task<IActionResult> Login(LoginDto dto)
    {
        if (string.IsNullOrWhiteSpace(dto.NIC) || string.IsNullOrWhiteSpace(dto.Password))
        {
            return BadRequest(new
            
            {
                Message = "NIC and Password are required."
            });
        }

        var user = await _users.Find(u => u.NIC == dto.NIC).FirstOrDefaultAsync();

        if (user == null)
        {
            return Unauthorized(new
            {
                message = "Invalid NIC or Password."
            });
        }

        if (!BCrypt.Net.BCrypt.Verify(dto.Password, user.PasswordHash))
        {
            return Unauthorized(new
            {
                message = "Invalid NIC or Password."
            });
        }

    // Only active users are allowed to log in.
        if (user.Status != "Active")
        {
            return Unauthorized(new
            {
                message = $"Account is {user.Status.ToLower()}."
            });
        }

    // Generate a signed JWT containing the authenticated user's details.
        var token = GenerateJwtToken(user);

        return Ok(new
        {
            message = "Login successful.",
            token = token,
            user = new
            {
                id = user.Id,
                nic = user.NIC,
                fullName = user.FullName,
                role = user.Role,
                status = user.Status
            }
        });
    }

// Generates a JWT token containing the user's ID, NIC, and role for the authenticated user.
    private string GenerateJwtToken(User user)
    {
        // Add identity and role information as JWT claims.
        var claims = new[]
        {
            new Claim(ClaimTypes.NameIdentifier, user.Id ?? ""),
            new Claim(ClaimTypes.Name, user.NIC),
            new Claim(ClaimTypes.Role, user.Role)
        };

    // Create the signing key using the secret key configured in appsettings.
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_configuration["JwtSettings:Key"]!));

    // Use HMAC-SHA256 to digitally sign the JWT.
        var credentials = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);

    // Read the token lifetime from configuration.
        var expiryInMinutes = int.Parse(_configuration["JwtSettings:ExpiryInMinutes"] ?? "120");

    // Create the JWT with issuer, audience, claims, expiry time, and signature.
        var token = new JwtSecurityToken(
            issuer: _configuration["JwtSettings:Issuer"],
            audience: _configuration["JwtSettings:Audience"],
            claims: claims,
            expires: DateTime.UtcNow.AddMinutes(expiryInMinutes),
            signingCredentials: credentials
        );

    // Serialize the JWT into a string that can be returned to the client.
        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}