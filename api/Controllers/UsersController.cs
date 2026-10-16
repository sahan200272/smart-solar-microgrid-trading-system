// File: UsersController.cs
// Component: User & Access Management  
// Description: Handles Backoffice and Grid Operator account management.

using System.Text.RegularExpressions;
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

    private static readonly Regex NicRegex = new(@"^(\d{9}[vVxX]|\d{12})$", RegexOptions.Compiled);
    private static readonly Regex EmailRegex = new(@"^[a-zA-Z0-9._%+-]+@gmail\.com$", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex PhoneRegex = new(@"^0\d{9}$", RegexOptions.Compiled);

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

        // Validate required account information.
        if (string.IsNullOrWhiteSpace(dto.NIC) || string.IsNullOrWhiteSpace(dto.Password) || string.IsNullOrWhiteSpace(dto.FullName))
        {
            return BadRequest(new
            {
                message = "NIC, Password, and FullName are required."
            });
        }

        if (!NicRegex.IsMatch(dto.NIC))
        {
            return BadRequest(new
            {
                message = "Invalid NIC format. Must be 9 digits followed by V/X or 12 digits."
            });
        }

        if (!string.IsNullOrWhiteSpace(dto.Email) && !EmailRegex.IsMatch(dto.Email))
        {
            return BadRequest(new
            {
                message = "Email must be a valid @gmail.com address."
            });
        }

        if (!string.IsNullOrWhiteSpace(dto.Phone) && !PhoneRegex.IsMatch(dto.Phone))
        {
            return BadRequest(new
            {
                message = "Phone number must contain 10 digits and start with 0."
            });
        }

        // Ensure the NIC is unique.
        var existingUser = await _users.Find(u => u.NIC == dto.NIC).FirstOrDefaultAsync();

        if (existingUser != null)
        {
            return Conflict(new
            {
                message = "A user with this NIC already exists."
            });
        }

        // Create the Backoffice account with an active status.
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

        if (!NicRegex.IsMatch(dto.NIC))
        {
            return BadRequest(new
            {
                message = "Invalid NIC format. Must be 9 digits followed by V/X or 12 digits."
            });
        }

        if (!string.IsNullOrWhiteSpace(dto.Email) && !EmailRegex.IsMatch(dto.Email))
        {
            return BadRequest(new
            {
                message = "Email must be a valid @gmail.com address."
            });
        }

        if (!string.IsNullOrWhiteSpace(dto.Phone) && !PhoneRegex.IsMatch(dto.Phone))
        {
            return BadRequest(new
            {
                message = "Phone number must contain 10 digits and start with 0."
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

        // Create the Grid Operator account.
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

        // Retrieve all user records from MongoDB.
        var users = await _users.Find(_ => true).ToListAsync();

        // Return selected fields without exposing password hashes.
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
    // Returns one user using the MongoDB user ID.
    [HttpGet("{id}")]
    public async Task<IActionResult> GetUserById(string id)
    {

        // Find the user using the MongoDB ID.
        var user = await _users.Find(u => u.Id == id).FirstOrDefaultAsync();
        if (user == null)
        {
            return NotFound(new
            {
                message = "User not found."
            });
        }

        // Return user information without exposing the password hash.
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

        // Find the target user by MongoDB ID.
        var user = await _users.Find(u => u.Id == id).FirstOrDefaultAsync();

        if (user == null)
        {
            return NotFound(new
            {
                message = "User not found."
            });
        }

        // Restrict account status to the supported application statuses.
        var allowedStatuses = new[] { "Pending", "Active", "Deactivated" };

        if (!allowedStatuses.Contains(dto.Status))
        {
            return BadRequest(new
            {
                message = "Invalid account status."
            });
        }

        if (!string.IsNullOrWhiteSpace(dto.Email) && !EmailRegex.IsMatch(dto.Email))
        {
            return BadRequest(new
            {
                message = "Email must be a valid @gmail.com address."
            });
        }

        if (!string.IsNullOrWhiteSpace(dto.Phone) && !PhoneRegex.IsMatch(dto.Phone))
        {
            return BadRequest(new
            {
                message = "Phone number must contain 10 digits and start with 0."
            });
        }

        // Update the user's profile and account status.
        var update = Builders<User>.Update
            .Set(u => u.FullName, dto.FullName)
            .Set(u => u.Email, dto.Email)
            .Set(u => u.Phone, dto.Phone)
            .Set(u => u.Address, dto.Address)
            .Set(u => u.Status, dto.Status)
            .Set(u => u.UpdatedAt, DateTime.UtcNow);

        await _users.UpdateOneAsync(u => u.Id == id, update);

        // Retrieve the updated record to return the latest information.
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

        // Perform a soft delete by changing the status to Deactivated.
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