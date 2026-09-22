// File: UsersController.cs
// Component: User & Access Management  
// Description: Handles Backoffice and Grid Operator account management.

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/users")]
[Authorize(Roles = "Backoffice")]
public class UsersController : ControllerBase
{
    private readonly IMongoCollection<User> _users;

    public UsersController(IMongoClient mongoClient, IConfiguration configuration)
    {
        var database = mongoClient.GetDatabase(configuration["MongoDbSettings:DatabaseName"]);
        _users = database.GetCollection<User>("Users");
    }

    // POST: api/users/backoffice
    // Creates a new Backoffice user account. Only an existing Backoffice user can perform this action.
    [HttpPost("backoffice")]
    public async Task<IActionResult> CreateBackofficeUser(CreateBackofficeUserDto dto)
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

        var user = new User
        {
            NIC = dto.NIC,
            FullName = dto.FullName,
            Email = dto.Email,
            Phone = dto.Phone,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(dto.Password),
            Role = "Backoffice",
            Status = "Active",
            CreatedAt = DateTime.UtcNow
        };

        await _users.InsertOneAsync(user);

        return Created("", new 
        {
            message = "Backoffice user created successfully.",
            user = new
            {
                user.Id,
                user.NIC,
                user.FullName,
                user.Email,
                user.Phone,
                user.Role,
                user.Status
            }
        });
    }

    // POST api/users/grid-operator
    // Creates a new Grid Operator user account.
    [HttpPost("gridoperator")]
    public async Task<IActionResult> CreateGridOperatorUser(CreateGridOperatorDto dto)
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

        var user = new User
        {
            NIC = dto.NIC,
            FullName = dto.FullName,
            Email = dto.Email,
            Phone = dto.Phone,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(dto.Password),
            Role = "GridOperator",
            Status = "Active",
            CreatedAt = DateTime.UtcNow
        };

        await _users.InsertOneAsync(user);

        return Created("", new 
        {
            message = "Grid Operator created successfully.",
            user = new
            {
                user.Id,
                user.NIC,
                user.FullName,
                user.Email,
                user.Phone,
                user.Role,
                user.Status
            }
        });
    }

    // GET: api/users
    // Returns all users for the Backoffice administration screen.
    [HttpGet]
    public async Task<IActionResult> GetAllUsers()
    {
        var users = await _users.Find(_ => true).ToListAsync();
        var result = users.Select(user => new
        {
            user.Id,
            user.NIC,
            user.FullName,
            user.Email,
            user.Phone,
            user.Role,
            user.Status
        });

        return Ok(result);
    }

    // GET api/users/{id}
    // Returns one user using the MngoDB user ID.
    [HttpGet("{id}")]
    public async Task<IActionResult> GetUserById(string id)
    {
        var user = await _users.Find(u => u.Id == id).FirstOrDefaultAsync();
        if (user == null)
        {
            return NotFound(new
            {
                message = "User not found."
            });
        }

        return Ok(new
        {
            user.Id,
            user.NIC,
            user.FullName,
            user.Email,
            user.Phone,
            user.Address,
            user.Role,
            user.Status,
            user.CreatedAt
        });
    }

    // PUT api/users/{id}
    // Update user information and allows Backoffice to change account status.
    [HttpPut("{id}")]
    public async Task<IActionResult> UpdateUser(string id, UpdateUserDto dto)
    {
        var user = await _users.Find(u => u.Id == id).FirstOrDefaultAsync();
        if (user == null)
        {
            return NotFound(new
            {
                message = "User not found."
            });
        }

        var allowedStatuses = new[] { "Pending", "Active", "Deactivated" };

        if (!allowedStatuses.Contains(dto.Status))
        {
            return BadRequest(new
            {
                message = "Invalid account status."
            });
        }

        var update = Builders<User>.Update
            .Set(u => u.FullName, dto.FullName)
            .Set(u => u.Email, dto.Email)
            .Set(u => u.Phone, dto.Phone)
            .Set(u => u.Address, dto.Address)
            .Set(u => u.Status, dto.Status)
            .Set(u => u.UpdatedAt, DateTime.UtcNow);

        await _users.UpdateOneAsync(u => u.Id == id, update);

        var updatedUser = await _users.Find(u => u.Id == id).FirstOrDefaultAsync();

        return Ok(new
        {
            message = "User updated successfully.",
            user = new
            {
                updatedUser!.Id,
                updatedUser.NIC,
                updatedUser.FullName,
                updatedUser.Email,
                updatedUser.Phone,
                updatedUser.Address,
                updatedUser.Role,
                updatedUser.Status
            }
        });
    }

    // DELETE api/users/{id}
    // Deactivates a user instead of physically deleting the MongoDB document.
    [HttpDelete("{id}")]
    public async Task<IActionResult> DeactivateUser(string id)
    {
        var user = await _users.Find(u => u.Id == id).FirstOrDefaultAsync();
        if (user == null)
        {
            return NotFound(new
            {
                message = "User not found."
            });
        }

        var update = Builders<User>.Update
            .Set(u => u.Status, "Deactivated")
            .Set(u => u.UpdatedAt, DateTime.UtcNow);

        await _users.UpdateOneAsync(u => u.Id == id, update);

        return Ok(new
        {
            message = "User deactivated successfully."
        });
    }

}