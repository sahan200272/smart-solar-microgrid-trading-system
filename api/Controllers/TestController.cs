using Microsoft.AspNetCore.Mvc;
using MongoDB.Driver;

namespace SolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api/[controller]")]
public class TestController : ControllerBase
{
    private readonly IMongoClient _mongoClient;
    private readonly IConfiguration _configuration;

    // Constructor: ASP.NET automatically hands us the MongoDB client
    // we registered earlier in Program.cs
    public TestController(IMongoClient mongoClient, IConfiguration configuration)
    {
        _mongoClient = mongoClient;
        _configuration = configuration;
    }

    [HttpGet("db-connection")]
    public IActionResult TestConnection()
    {
        try
        {
            var databaseName = _configuration["MongoDbSettings:DatabaseName"];
            var database = _mongoClient.GetDatabase(databaseName);

            // Ping command - the standard way to check if MongoDB is reachable
            var result = database.RunCommand<MongoDB.Bson.BsonDocument>(
                new MongoDB.Bson.BsonDocument("ping", 1));

            return Ok(new { status = "success", message = "Connected to MongoDB!", database = databaseName });
        }
        catch (Exception ex)
        {
            return StatusCode(500, new { status = "failed", message = ex.Message });
        }
    }
}