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
var jwtKey = builder.Configuration["JwtSettings:Key"];

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
    }
}
catch (Exception ex)
{
    app.Logger.LogWarning(ex, "Failed to initialize unique index on qrToken.");
}

app.MapControllers();

app.Run();