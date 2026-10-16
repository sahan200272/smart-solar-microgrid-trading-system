using System.Net;
using System.Net.Http.Json;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using Xunit;

namespace SolarMicrogrid.Tests.IntegrationTests;

public class NodesIntegrationTests
{
    [Fact]
    public async Task CreateGetUpdateAndDeactivateNode_HttpPipeline_WorksAsExpected()
    {
        using var factory = new CustomWebApplicationFactory();
        var backofficeClient = factory.CreateAuthenticatedClient("admin001", "Backoffice");
        var prosumerClient = factory.CreateAuthenticatedClient("200211112222", "Prosumer");

        // 1. Prosumer cannot create a node -> 403 Forbidden
        var forbiddenCreate = await prosumerClient.PostAsJsonAsync("/api/nodes", new CreateNodeDto
        {
            StationName = "Unauthorized Hub",
            TotalBatterySlots = 4,
            CapacityKWh = 100
        });
        Assert.Equal(HttpStatusCode.Forbidden, forbiddenCreate.StatusCode);

        // 2. Backoffice creates a node -> 201 Created
        var createResponse = await backofficeClient.PostAsJsonAsync("/api/nodes", new CreateNodeDto
        {
            StationName = "Negombo Beach Hub",
            Latitude = 7.2083,
            Longitude = 79.8358,
            CapacityKWh = 150.0,
            TotalBatterySlots = 5,
            OperatingSchedule = "06:00 - 22:00"
        });
        Assert.Equal(HttpStatusCode.Created, createResponse.StatusCode);

        var createdNode = await createResponse.Content.ReadFromJsonAsync<SolarStationInfo>();
        Assert.NotNull(createdNode);
        Assert.False(string.IsNullOrEmpty(createdNode.Id));
        Assert.Equal(5, createdNode.AvailableBatterySlots);

        // 3. Prosumer can query nearby nodes -> 200 OK
        var nearbyResponse = await prosumerClient.GetAsync("/api/nodes/nearby?lat=7.2083&lng=79.8358&radiusKm=10");
        Assert.Equal(HttpStatusCode.OK, nearbyResponse.StatusCode);
        var nearbyNodes = await nearbyResponse.Content.ReadFromJsonAsync<List<SolarStationInfo>>();
        Assert.NotNull(nearbyNodes);
        Assert.Single(nearbyNodes);

        // 4. Backoffice updates node specs -> 200 OK
        var updateResponse = await backofficeClient.PutAsJsonAsync($"/api/nodes/{createdNode.Id}", new UpdateNodeDto
        {
            StationName = "Negombo Beach Hub Updated",
            Latitude = 7.2083,
            Longitude = 79.8358,
            CapacityKWh = 200.0,
            TotalBatterySlots = 8,
            OperatingSchedule = "05:00 - 23:00"
        });
        Assert.Equal(HttpStatusCode.OK, updateResponse.StatusCode);

        // 5. Backoffice deactivates node -> 200 OK
        var deactivateResponse = await backofficeClient.PutAsync($"/api/nodes/{createdNode.Id}/deactivate", null);
        Assert.Equal(HttpStatusCode.OK, deactivateResponse.StatusCode);
    }
}
