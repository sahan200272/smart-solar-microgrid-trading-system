using System.Net.Http.Headers;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using MongoDB.Driver;
using SolarMicrogrid.Tests.TestHelpers;

namespace SolarMicrogrid.Tests.IntegrationTests;

public class CustomWebApplicationFactory : WebApplicationFactory<Program>
{
    public InMemoryMongoStore MongoStore { get; } = new();

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseSetting("MongoDbSettings:ConnectionString", "mongodb://localhost:27017");
        builder.UseSetting("MongoDbSettings:DatabaseName", TestAuthHelper.TestDatabaseName);
        builder.UseSetting("JwtSettings:Key", TestAuthHelper.TestJwtKey);
        builder.UseSetting("JwtSettings:Issuer", TestAuthHelper.TestJwtIssuer);
        builder.UseSetting("JwtSettings:Audience", TestAuthHelper.TestJwtAudience);
        builder.UseSetting("JwtSettings:ExpiryInMinutes", "120");

        builder.ConfigureAppConfiguration((_, configBuilder) =>
        {
            configBuilder.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["MongoDbSettings:ConnectionString"] = "mongodb://localhost:27017",
                ["MongoDbSettings:DatabaseName"] = TestAuthHelper.TestDatabaseName,
                ["JwtSettings:Key"] = TestAuthHelper.TestJwtKey,
                ["JwtSettings:Issuer"] = TestAuthHelper.TestJwtIssuer,
                ["JwtSettings:Audience"] = TestAuthHelper.TestJwtAudience,
                ["JwtSettings:ExpiryInMinutes"] = "120"
            });
        });

        builder.ConfigureServices(services =>
        {
            services.RemoveAll<IMongoClient>();
            services.AddSingleton<IMongoClient>(MongoStore.Client);
        });
    }

    public HttpClient CreateAuthenticatedClient(string nic, string role, string? userId = null)
    {
        var client = CreateClient();
        var token = TestAuthHelper.GenerateTestJwtToken(nic, role, userId);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }
}
