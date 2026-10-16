using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using SolarMicrogrid.Api.DTOs;
using Xunit;

namespace SolarMicrogrid.Tests.IntegrationTests;

public class AuthAndUsersIntegrationTests
{
    [Fact]
    public async Task ProsumerRegistration_Activation_AndLoginFlow_WorksEndToEnd()
    {
        using var factory = new CustomWebApplicationFactory();
        var anonymousClient = factory.CreateClient();
        var backofficeClient = factory.CreateAuthenticatedClient("admin001", "Backoffice");

        // 1. Register a new Prosumer (anonymous endpoint)
        var registerDto = new RegisterProsumerDto
        {
            NIC = "200255556666",
            FullName = "Nimal Perera",
            Email = "nimal@gmail.com",
            Phone = "0771112223",
            Address = "Colombo 07",
            Password = "StrongPassword123!"
        };

        var registerResponse = await anonymousClient.PostAsJsonAsync("/api/auth/register/prosumer", registerDto);
        Assert.Equal(HttpStatusCode.Created, registerResponse.StatusCode);

        // 2. Attempt to log in before Backoffice activation -> 401 Unauthorized
        var loginBeforeActivation = await anonymousClient.PostAsJsonAsync("/api/auth/login", new LoginDto
        {
            NIC = "200255556666",
            Password = "StrongPassword123!"
        });
        Assert.Equal(HttpStatusCode.Unauthorized, loginBeforeActivation.StatusCode);

        // 3. Backoffice activates the pending Prosumer account
        var activateResponse = await backofficeClient.PutAsync("/api/prosumers/200255556666/activate", null);
        Assert.Equal(HttpStatusCode.OK, activateResponse.StatusCode);

        // 4. Prosumer logs in after activation -> 200 OK with JWT token
        var loginAfterActivation = await anonymousClient.PostAsJsonAsync("/api/auth/login", new LoginDto
        {
            NIC = "200255556666",
            Password = "StrongPassword123!"
        });
        Assert.Equal(HttpStatusCode.OK, loginAfterActivation.StatusCode);

        var loginJson = await loginAfterActivation.Content.ReadFromJsonAsync<JsonElement>();
        var jwtToken = loginJson.GetProperty("token").GetString();
        Assert.False(string.IsNullOrWhiteSpace(jwtToken));

        // 5. Use the issued JWT token to fetch own Prosumer profile
        anonymousClient.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", jwtToken);
        var profileResponse = await anonymousClient.GetAsync("/api/prosumers/200255556666");
        Assert.Equal(HttpStatusCode.OK, profileResponse.StatusCode);
    }

    [Fact]
    public async Task UsersEndpoints_EnforceAuthenticationAndRoleAuthorization()
    {
        using var factory = new CustomWebApplicationFactory();
        var unauthenticatedClient = factory.CreateClient();
        var prosumerClient = factory.CreateAuthenticatedClient("200211112222", "Prosumer");
        var backofficeClient = factory.CreateAuthenticatedClient("admin001", "Backoffice");

        // Unauthenticated -> 401 Unauthorized
        var unauthResponse = await unauthenticatedClient.GetAsync("/api/users");
        Assert.Equal(HttpStatusCode.Unauthorized, unauthResponse.StatusCode);

        // Prosumer role -> 403 Forbidden
        var forbidResponse = await prosumerClient.GetAsync("/api/users");
        Assert.Equal(HttpStatusCode.Forbidden, forbidResponse.StatusCode);

        // Backoffice role -> 201 Created when creating GridOperator, 200 OK when listing users
        var createOpResponse = await backofficeClient.PostAsJsonAsync("/api/users/gridoperator", new CreateGridOperatorDto
        {
            NIC = "199511112222",
            FullName = "Grid Operator One",
            Email = "op1@gmail.com",
            Phone = "0712223334",
            Password = "OperatorPass123!"
        });
        Assert.Equal(HttpStatusCode.Created, createOpResponse.StatusCode);

        var listResponse = await backofficeClient.GetAsync("/api/users");
        Assert.Equal(HttpStatusCode.OK, listResponse.StatusCode);
    }
}
