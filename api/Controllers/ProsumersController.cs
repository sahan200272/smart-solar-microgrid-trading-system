// File: ProsumersController.cs
// Component: User & Access Management
// Description: Handles Prosumer registration, profile management, deactivation and reactivation.

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/prosumers")]
public class ProsumersController : ControllerBase
{
    private readonly IMongoCollection<User> _users;

    public ProsumersController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _users = database.GetCollection<User>("Users");
    }

    // POST: api/auth/register/prosumer
    // Creates a new Prosumer account using NIC as the unique identifier.
    [HttpPost("/api/auth/register/prosumer")]
    [AllowAnonymous]
    public async Task<IActionResult> RegisterProsumer(RegisterProsumerDto dto)
    {
        if (string.IsNullOrWhiteSpace(dto.NIC) || string.IsNullOrWhiteSpace(dto.Password) || string.IsNullOrWhiteSpace(dto.FullName))
        {
            return BadRequest(new
            {
                message = "NIC, Password, and FullName are required."
            });
        }

        var existingUser = await _users.Find(u => u.NIC == dto.NIC).FirstOrDefaultAsync();
        if (existingUser != null)
        {
            return Conflict(new
            {
                message = "A user with this NIC already exists."
            });
        }

        var prosumer = new User
        {
            NIC = dto.NIC,
            FullName = dto.FullName,
            Email = dto.Email,
            Phone = dto.Phone,
            Address = dto.Address,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(dto.Password),
            Role = "Prosumer",
            Status = "Pending",
            CreatedAt = DateTime.UtcNow
        };

        await _users.InsertOneAsync(prosumer);

        return Created("", new
        {
            message = "Prosumer registration successful. Waiting for Backoffice activation.",
            nic = prosumer.NIC,
            status = prosumer.Status
        });
    }

    // GET: api/prosumers
    // Returns all Prosumer accounts for Backoffice and Grid Operator users.
    [HttpGet]
    [Authorize(Roles = "Backoffice,GridOperator")]
    public async Task<IActionResult> GetAllProsumers()
    {
        var prosumers = await _users.Find(u => u.Role == "Prosumer").ToListAsync();
        return Ok(prosumers.Select(p => new
        {
            p.Id,
            p.NIC,
            p.FullName,
            p.Email,
            p.Phone,
            p.Address,
            p.Status,
            p.CreatedAt
        }));
    }

    // GET api/prosumers/pending
    // Returns Prosumer accounts waiting for Backoffice activation.
    [HttpGet("pending")]
    [Authorize(Roles = "Backoffice")]
    public async Task<IActionResult> GetPendingProsumers()
    {
        var pending = await _users.Find(u => u.Role == "Prosumer" && u.Status == "Pending").ToListAsync();
        return Ok(pending.Select(p => new
        {
            p.Id,
            p.NIC,
            p.FullName,
            p.Email,
            p.Phone,
            p.Address,
            p.Status,
            p.CreatedAt
        }));
    }

    //GET api/prosumers/{nic}
    // Returns a Prosumer account using NIC as the primary identifier.
    [HttpGet("{nic}")]
    [Authorize(Roles = "Backoffice,GridOperator,Prosumer")]
    public async Task<IActionResult> GetProsumerByNIC(string nic)
    {
        var prosumer = await _users.Find(u => u.NIC == nic && u.Role == "Prosumer").FirstOrDefaultAsync();
        if (prosumer == null)
        {
            return NotFound(new
            {
                message = "Prosumer not found."
            });
        }

        return Ok(new
        {
            prosumer.Id,
            prosumer.NIC,
            prosumer.FullName,
            prosumer.Email,
            prosumer.Phone,
            prosumer.Address,
            prosumer.Status,
            prosumer.CreatedAt
        });
    }

    // PUT api/prosumers/{nic}
    // Allows a Prosumer to update their own profile information.
    [HttpPut("{nic}")]
    [Authorize(Roles = "Prosumer")]
    public async Task<IActionResult> UpdateProsumer(string nic, UpdateProsumerDto dto)
    {
        // The logged-in user's NIC is stored inside the JWT.
        var loggedInNic = User.Identity?.Name;
        if (loggedInNic != nic)
        {
            return Forbid();
        }

        var prosumer = await _users.Find(u => u.NIC == nic && u.Role == "Prosumer").FirstOrDefaultAsync();
        if (prosumer == null)
        {
            return NotFound(new
            {
                message = "Prosumer not found."
            });
        }

        if (prosumer.Status != "Active")
        {
            return BadRequest(new
            {
                message = "Only active accounts can be updated."
            });
        }

        var update = Builders<User>.Update
            .Set(u => u.FullName, dto.FullName ?? prosumer.FullName)
            .Set(u => u.Email, dto.Email ?? prosumer.Email)
            .Set(u => u.Phone, dto.Phone ?? prosumer.Phone)
            .Set(u => u.Address, dto.Address ?? prosumer.Address)
            .Set(u => u.UpdatedAt, DateTime.UtcNow);

        await _users.UpdateOneAsync(u => u.NIC == nic, update);

        return Ok(new
        {
            message = "Prosumer profile updated successfully."
        });
    }

    // PUT api/prosumers/{nic}/deactivate
    // Allows the Prosumer to request deactivation of their own account.
    [HttpPut("{nic}/deactivate")]
    [Authorize(Roles = "Prosumer,Backoffice")]
    public async Task<IActionResult> DeactivateProsumer(string nic)
    {
        var prosumer = await _users.Find(u => u.NIC == nic && u.Role == "Prosumer").FirstOrDefaultAsync();
        if (prosumer == null)
        {
            return NotFound(new
            {
                message = "Prosumer not found."
            });
        }

        if (User.IsInRole("Prosumer") && User.Identity?.Name != nic)
        {
            return Forbid();
        }

        if (prosumer.Status == "Deactivated")
        {
            return BadRequest(new
            {
                message = "Prosumer is already deactivated."
            });
        }

        var update = Builders<User>.Update
            .Set(u => u.Status, "Deactivated")
            .Set(u => u.UpdatedAt, DateTime.UtcNow);

        await _users.UpdateOneAsync(u => u.NIC == nic, update);

        return Ok(new
        {
            message = "Prosumer deactivated successfully."
        });
    }

    // PUT api/prosumers/{nic}/reactivate
    // Allows only a Backoffice user to reactivate a deactivated Prosumer account.
    [HttpPut("{nic}/reactivate")]
    [Authorize(Roles = "Backoffice")]
    public async Task<IActionResult> ReactivateProsumer(string nic)
    {
        var prosumer = await _users.Find(u => u.NIC == nic && u.Role == "Prosumer").FirstOrDefaultAsync();
        if (prosumer == null)
        {
            return NotFound(new
            {
                message = "Prosumer not found."
            });
        }

        if (prosumer.Status != "Deactivated")
        {
            return BadRequest(new
            {
                message = "Only deactivated accounts can be reactivated."
            });
        }

        var update = Builders<User>.Update
            .Set(u => u.Status, "Active")
            .Set(u => u.UpdatedAt, DateTime.UtcNow);

        await _users.UpdateOneAsync(u => u.NIC == nic, update);

        return Ok(new
        {
            message = "Prosumer reactivated successfully."
        });
    }


}