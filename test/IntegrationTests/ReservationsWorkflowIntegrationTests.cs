using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using SolarMicrogrid.Api.DTOs;
using SolarMicrogrid.Api.Models;
using Xunit;

namespace SolarMicrogrid.Tests.IntegrationTests;

public class ReservationsWorkflowIntegrationTests
{
    [Fact]
    public async Task FullReservationLifecycle_Create_Approve_GenerateQr_Verify_AndFinalize_Succeeds()
    {
        using var factory = new CustomWebApplicationFactory();
        var backofficeClient = factory.CreateAuthenticatedClient("admin001", "Backoffice", "507f1f77bcf86cd799439001");
        var operatorClient = factory.CreateAuthenticatedClient("operator001", "GridOperator", "507f1f77bcf86cd799439002");
        var prosumerNic = "200277778888";
        var prosumerClient = factory.CreateAuthenticatedClient(prosumerNic, "Prosumer", "507f1f77bcf86cd799439003");

        // Seed active Prosumer user in store (needed by QR verification)
        factory.MongoStore.Seed("Users", new User
        {
            Id = "507f1f77bcf86cd799439003",
            NIC = prosumerNic,
            FullName = "Sahan Prosumer",
            Phone = "0771234567",
            Role = "Prosumer",
            Status = "Active"
        });

        // 1. Backoffice creates an active Solar Station Node
        var createNodeResponse = await backofficeClient.PostAsJsonAsync("/api/nodes", new CreateNodeDto
        {
            StationName = "Moratuwa Solar Hub",
            Latitude = 6.7951,
            Longitude = 79.9009,
            CapacityKWh = 120.0,
            TotalBatterySlots = 4,
            OperatingSchedule = "06:00 - 22:00"
        });
        Assert.Equal(HttpStatusCode.Created, createNodeResponse.StatusCode);
        var node = await createNodeResponse.Content.ReadFromJsonAsync<SolarStationInfo>();
        Assert.NotNull(node);

        // 2. Prosumer creates a reservation starting in 15 minutes (within 30-min early scan grace window for QR verification)
        var slotStart = DateTime.UtcNow.AddMinutes(15);
        var slotEnd = slotStart.AddHours(2);

        var createResResponse = await prosumerClient.PostAsJsonAsync("/api/reservations", new CreateReservationDto
        {
            ProsumerNic = prosumerNic,
            NodeId = node.Id!,
            StationName = node.StationName,
            EnergyKWh = 25.0,
            SlotStartTime = slotStart,
            SlotEndTime = slotEnd
        });
        Assert.Equal(HttpStatusCode.Created, createResResponse.StatusCode);
        var createdRes = await createResResponse.Content.ReadFromJsonAsync<ReservationResponseDto>();
        Assert.NotNull(createdRes);
        Assert.Equal("Pending", createdRes.Status);

        // 3. Grid Operator approves the pending reservation
        var approveResponse = await operatorClient.PutAsync($"/api/reservations/{createdRes.Id}/approve", null);
        Assert.Equal(HttpStatusCode.OK, approveResponse.StatusCode);

        // 4. Prosumer generates a QR code for the approved reservation
        var qrResponse = await prosumerClient.PostAsync($"/api/reservations/{createdRes.Id}/qr", null);
        Assert.Equal(HttpStatusCode.OK, qrResponse.StatusCode);
        var qrJson = await qrResponse.Content.ReadFromJsonAsync<JsonElement>();
        var qrPayload = qrJson.GetProperty("qrPayload").GetString();
        Assert.NotNull(qrPayload);
        Assert.StartsWith("SSMG:1:", qrPayload);

        // 5. Grid Operator verifies the scanned QR code
        var verifyResponse = await operatorClient.PostAsJsonAsync("/api/reservations/verify-qr", new VerifyQrDto
        {
            QrToken = qrPayload,
            NodeId = node.Id
        });
        Assert.Equal(HttpStatusCode.OK, verifyResponse.StatusCode);
        var verifyJson = await verifyResponse.Content.ReadFromJsonAsync<JsonElement>();
        Assert.True(verifyJson.GetProperty("valid").GetBoolean());

        // 6. Grid Operator finalizes the energy transfer
        var finalizeResponse = await operatorClient.PutAsJsonAsync($"/api/reservations/{createdRes.Id}/finalize", new FinalizeReservationDto
        {
            QrToken = qrPayload,
            NodeId = node.Id,
            DeliveredKWh = 25.0
        });
        Assert.Equal(HttpStatusCode.OK, finalizeResponse.StatusCode);

        // 7. Re-scanning the used QR token returns 409 Conflict (ALREADY_USED)
        var reuseResponse = await operatorClient.PostAsJsonAsync("/api/reservations/verify-qr", new VerifyQrDto
        {
            QrToken = qrPayload,
            NodeId = node.Id
        });
        Assert.Equal(HttpStatusCode.Conflict, reuseResponse.StatusCode);
    }

    [Fact]
    public async Task CancelReservation_RestoresSlotAvailability_OverHttp()
    {
        using var factory = new CustomWebApplicationFactory();
        var backofficeClient = factory.CreateAuthenticatedClient("admin001", "Backoffice");
        var prosumerNic = "200233334444";
        var prosumerClient = factory.CreateAuthenticatedClient(prosumerNic, "Prosumer");

        var nodeResponse = await backofficeClient.PostAsJsonAsync("/api/nodes", new CreateNodeDto
        {
            StationName = "Kandy Solar Hub",
            Latitude = 7.2906,
            Longitude = 80.6337,
            CapacityKWh = 100.0,
            TotalBatterySlots = 2,
            OperatingSchedule = "06:00 - 20:00"
        });
        var node = await nodeResponse.Content.ReadFromJsonAsync<SolarStationInfo>();
        Assert.NotNull(node);

        // Create reservation 2 days in the future (more than 12 hours notice)
        var start = DateTime.UtcNow.AddDays(2);
        var createResResponse = await prosumerClient.PostAsJsonAsync("/api/reservations", new CreateReservationDto
        {
            ProsumerNic = prosumerNic,
            NodeId = node.Id!,
            EnergyKWh = 40.0,
            SlotStartTime = start,
            SlotEndTime = start.AddHours(2)
        });
        Assert.Equal(HttpStatusCode.Created, createResResponse.StatusCode);
        var createdRes = await createResResponse.Content.ReadFromJsonAsync<ReservationResponseDto>();
        Assert.NotNull(createdRes);

        // Cancel reservation via DELETE /api/reservations/{id}
        var cancelResponse = await prosumerClient.DeleteAsync($"/api/reservations/{createdRes.Id}");
        Assert.Equal(HttpStatusCode.OK, cancelResponse.StatusCode);

        // Check slot capacity was restored to 100 kWh and 2 available slots
        var slotResponse = await prosumerClient.GetAsync($"/api/slots/{createdRes.SlotId}");
        Assert.Equal(HttpStatusCode.OK, slotResponse.StatusCode);
        var slot = await slotResponse.Content.ReadFromJsonAsync<EnergyBookingSlot>();
        Assert.NotNull(slot);
        Assert.Equal(100.0, slot.AvailableCapacityKWh);
        Assert.Equal(2, slot.AvailableSlotCount);
    }
}
