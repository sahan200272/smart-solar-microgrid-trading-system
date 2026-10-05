using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using MongoDB.Driver;
using System.Text;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddControllers();

// Register MongoDB client as a singleton (one shared connection for the whole app)
builder.Services.AddSingleton<IMongoClient>(sp =>
{
    var configuration = sp.GetRequiredService<IConfiguration>();

    var connectionString = configuration["MongoDbSettings:ConnectionString"];
    return new MongoClient(connectionString);
});

// JWT configuration
var jwtKey = builder.Configuration["JwtSettings:Key"]
    ?? throw new InvalidOperationException(
        "JWT key is missing from configuration."
    );

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidateAudience = true,
            ValidateLifetime = true,
            ValidateIssuerSigningKey = true,

            ValidIssuer = builder.Configuration["JwtSettings:Issuer"],
            ValidAudience = builder.Configuration["JwtSettings:Audience"],

            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtKey)
            )
        };
    });

    builder.Services.AddAuthorization();


// Add CORS policy - allows your web app (running on a browser) to call this API.
// "AllowAll" is fine for development; you'd tighten this for production.
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAll", policy =>
    {
        policy.AllowAnyOrigin()
              .AllowAnyMethod()
              .AllowAnyHeader();
    });
});

// Learn more about configuring Swagger/OpenAPI at https://aka.ms/aspnetcore/swashbuckle
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseHttpsRedirection();

app.UseCors("AllowAll");

app.UseAuthentication();
app.UseAuthorization();

// Ensure MongoDB unique index on QrToken for fast indexed lookups and DB-level uniqueness
try
{
    var mongoClient = app.Services.GetRequiredService<IMongoClient>();
    var dbName = app.Configuration["MongoDbSettings:DatabaseName"];
    if (!string.IsNullOrEmpty(dbName))
    {
        var db = mongoClient.GetDatabase(dbName);
        var rawReservations = db.GetCollection<MongoDB.Bson.BsonDocument>("EnergyReservation");
        // Unset any legacy null qrToken fields so MongoDB does not treat null as duplicate key
        rawReservations.UpdateMany(
            Builders<MongoDB.Bson.BsonDocument>.Filter.Eq("qrToken", MongoDB.Bson.BsonNull.Value),
            Builders<MongoDB.Bson.BsonDocument>.Update.Unset("qrToken")
        );

        var reservations = db.GetCollection<SolarMicrogrid.Api.Models.EnergyReservation>("EnergyReservation");
        var existingIndexes = reservations.Indexes.List().ToList();
        var hasQrIndex = existingIndexes.Any(doc =>
            doc.Contains("name") && doc["name"].AsString.Contains("qrToken", StringComparison.OrdinalIgnoreCase) ||
            doc.Contains("key") && doc["key"].AsBsonDocument.Contains("qrToken"));

        if (!hasQrIndex)
        {
            var indexKeys = Builders<SolarMicrogrid.Api.Models.EnergyReservation>.IndexKeys.Ascending(r => r.QrToken);
            var indexOptions = new CreateIndexOptions<SolarMicrogrid.Api.Models.EnergyReservation>
            {
                Unique = true,
                PartialFilterExpression = Builders<SolarMicrogrid.Api.Models.EnergyReservation>.Filter.Type(r => r.QrToken, MongoDB.Bson.BsonType.String),
                Name = "ux_qrToken"
            };
            reservations.Indexes.CreateOne(new CreateIndexModel<SolarMicrogrid.Api.Models.EnergyReservation>(indexKeys, indexOptions));
        }

        // Seed initial EnergyBookingSlots if collection is empty
        var slotCollection = db.GetCollection<SolarMicrogrid.Api.Models.EnergyBookingSlot>("EnergyBookingSlots");
        var slotCount = await slotCollection.CountDocumentsAsync(_ => true);
        if (slotCount == 0)
        {
            var nodeCollection = db.GetCollection<SolarMicrogrid.Api.Models.SolarStationInfo>("SolarStationInfo");
            var activeNodes = await nodeCollection.Find(n => n.IsActive).ToListAsync();
            var sampleSlots = new List<SolarMicrogrid.Api.Models.EnergyBookingSlot>();
            var now = DateTime.UtcNow;

            foreach (var node in activeNodes)
            {
                var cap = node.CapacityKWh > 0 ? node.CapacityKWh : 150.0;
                var slots = node.TotalBatterySlots > 0 ? node.TotalBatterySlots : 4;

                // Create slots for the next 3 days
                for (int day = 1; day <= 3; day++)
                {
                    var targetDate = now.Date.AddDays(day);

                    // Slot 1: 08:00 - 10:00 UTC
                    sampleSlots.Add(new SolarMicrogrid.Api.Models.EnergyBookingSlot
                    {
                        NodeId = node.Id!,
                        StationName = node.StationName,
                        SlotStartTime = targetDate.AddHours(8),
                        SlotEndTime = targetDate.AddHours(10),
                        TotalCapacityKWh = cap,
                        AvailableCapacityKWh = cap,
                        TotalSlotCount = slots,
                        AvailableSlotCount = slots,
                        Status = "Open",
                        ReservationIds = new List<string>(),
                        CreatedAt = now,
                        UpdatedAt = now
                    });

                    // Slot 2: 10:00 - 12:00 UTC
                    sampleSlots.Add(new SolarMicrogrid.Api.Models.EnergyBookingSlot
                    {
                        NodeId = node.Id!,
                        StationName = node.StationName,
                        SlotStartTime = targetDate.AddHours(10),
                        SlotEndTime = targetDate.AddHours(12),
                        TotalCapacityKWh = cap,
                        AvailableCapacityKWh = cap,
                        TotalSlotCount = slots,
                        AvailableSlotCount = slots,
                        Status = "Open",
                        ReservationIds = new List<string>(),
                        CreatedAt = now,
                        UpdatedAt = now
                    });

                    // Slot 3: 14:00 - 16:00 UTC
                    sampleSlots.Add(new SolarMicrogrid.Api.Models.EnergyBookingSlot
                    {
                        NodeId = node.Id!,
                        StationName = node.StationName,
                        SlotStartTime = targetDate.AddHours(14),
                        SlotEndTime = targetDate.AddHours(16),
                        TotalCapacityKWh = cap,
                        AvailableCapacityKWh = cap,
                        TotalSlotCount = slots,
                        AvailableSlotCount = slots,
                        Status = "Open",
                        ReservationIds = new List<string>(),
                        CreatedAt = now,
                        UpdatedAt = now
                    });
                }
            }

            if (sampleSlots.Count > 0)
            {
                await slotCollection.InsertManyAsync(sampleSlots);
                app.Logger.LogInformation("Seeded {Count} sample EnergyBookingSlots.", sampleSlots.Count);
            }
        }
    }
}
catch (Exception ex)
{
    app.Logger.LogWarning(ex, "Failed to initialize database indexes or sample slots.");
}

app.MapControllers();

app.Run();