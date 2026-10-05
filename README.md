# Smart Solar Microgrid Trading System

A student project for managing solar microgrid stations, battery booking slots, energy reservations, and QR-based energy-transfer verification.

The repository contains three clients/components:

- `api/`: ASP.NET Core Web API targeting .NET 8.
- `web/`: Static Bootstrap web portal for Backoffice users, Grid Operators, and Prosumers.
- `mobile/`: Kotlin Android application for Prosumer and Grid Operator workflows.

MongoDB is used for persistence. Authentication is handled by JWT bearer tokens, and passwords are stored as BCrypt hashes.

## Features

### Authentication and accounts

- Prosumer self-registration with NIC, profile details, and password.
- Backoffice activation of pending Prosumer accounts.
- Backoffice creation and management of Backoffice and Grid Operator accounts.
- Login using NIC and password.
- Role-based authorization for `Prosumer`, `Backoffice`, and `GridOperator`.
- Account states: `Pending`, `Active`, and `Deactivated`.

### Microgrid nodes and slots

- Create, view, update, and deactivate solar microgrid nodes.
- Store station name, GPS coordinates, capacity, battery-slot counts, and operating schedule.
- Find active nodes near a latitude/longitude point.
- View booking slots by node, date, or status.
- Update slot status and remaining capacity as a Backoffice user or Grid Operator.
- On first API startup, create sample slots for active nodes when the `EnergyBookingSlots` collection is empty.

### Reservations and energy transfer

- Create reservations for an active node and an energy quantity in kWh.
- Check slot capacity and available battery slots before booking.
- Reservation start times must be in the future and no more than seven days ahead.
- Modify, approve, cancel, and view reservation history and dashboard summaries.
- Generate a single-use QR token for an approved reservation.
- Grid Operators can verify the QR token and finalize the energy transfer.
- Record delivered energy, completion time, and the operator who finalized the transfer.

The reservation statuses implemented by the API are `Pending`, `Approved`, `Cancelled`, and `Completed`. The API also enforces a 12-hour notice rule for reservation changes and cancellations where applicable.

## Architecture

```text
Web browser  ──┐
Android app   ──┼──> ASP.NET Core API ──> MongoDB
Postman       ──┘
```

The API registers one shared `IMongoClient`, enables permissive CORS for development, exposes Swagger in the Development environment, and listens on the HTTP development URL `http://localhost:5098` by default.

## Prerequisites

- .NET 8 SDK
- MongoDB Atlas or a local MongoDB server
- Java 11 or later and Android SDK for the mobile application
- Android Studio for running or deploying the Android app
- A Google Maps API key for the mobile map screen
- Python or another static-file server for serving the web portal locally

## Configuration

### API

Edit `api/appsettings.json` or provide environment-specific configuration values:

```json
{
	"MongoDbSettings": {
		"ConnectionString": "YOUR_MONGODB_CONNECTION_STRING",
		"DatabaseName": "SolarMicrogridDB"
	},
	"JwtSettings": {
		"Key": "YOUR_LONG_RANDOM_SIGNING_KEY",
		"Issuer": "SolarMicrogridAPI",
		"Audience": "SolarMicrogridClients",
		"ExpiryInMinutes": 120
	}
}
```

Do not commit MongoDB credentials, JWT signing keys, or other secrets. Use user secrets, environment variables, or an untracked development settings file for local values. The API requires `MongoDbSettings:ConnectionString`, `MongoDbSettings:DatabaseName`, and `JwtSettings:Key` at startup.

### Web portal

The web client reads its API URL from `web/js/config.js`:

```javascript
const API_BASE_URL = "http://localhost:5098/api";
```

Change this value when the API is hosted at another address. The web client stores the JWT and logged-in user information in browser `localStorage`.

### Android app

`mobile/app/build.gradle.kts` creates the Retrofit base URL from `mobile/local.properties` or detects a local network address. Add or update these local properties:

```properties
MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
API_BASE_URL=http://YOUR_COMPUTER_IP:5098/
```

The mobile Retrofit service includes the `api/` path in each endpoint, so `API_BASE_URL` should normally end at the server root with a trailing slash, not at `/api/`. For the Android emulator, `http://10.0.2.2:5098/` reaches the development machine. A physical phone normally needs the computer's LAN IP, and both devices must be on the same network.

The repository ignores `mobile/local.properties`; create it locally if it is not present.

## Run the API

From the repository root on Windows:

```powershell
dotnet restore .\api\SolarMicrogrid.Api.csproj
dotnet run --project .\api\SolarMicrogrid.Api.csproj --launch-profile http
```

The API is then available at `http://localhost:5098`. In Development, Swagger UI is available at:

```text
http://localhost:5098/swagger
```

The API connects to MongoDB during startup and attempts to create the unique QR-token index and initial booking slots. Ensure MongoDB is reachable before starting the API.

There is no API endpoint that creates the first Backoffice account anonymously. A Backoffice account must already exist in the database or be provisioned through an appropriate administrative/bootstrap process before using Backoffice-only endpoints.

## Run the web portal

Start the API first, confirm that `web/js/config.js` points to it, then serve the `web` directory with a static server:

```powershell
py -m http.server 5500 --directory .\web
```

Open [http://localhost:5500/](http://localhost:5500/) in a browser. The root page redirects to `pages/login.html` when no session exists and to `pages/dashboard.html` when a JWT is already stored.

The web pages include:

- Login and role-aware dashboard
- User management for Backoffice users
- Prosumer directory and account status management
- Microgrid node management
- Reservation creation, filtering, approval, modification, and cancellation
- Grid Operator dashboard with pending and today's bookings
- QR scanning/manual QR entry and energy-transfer finalization

## Build the Android app

From the `mobile` directory:

```powershell
cd mobile
.\gradlew.bat assembleDebug
```

The debug APK is generated under `mobile/app/build/outputs/apk/debug/`. It can also be run from Android Studio after opening the `mobile` directory as a Gradle project.

The Android app provides Prosumer registration, login, profile management, nearby-node lookup using Google Maps, reservation creation and modification, booking history, QR pass generation, and Grid Operator dashboard/QR verification screens.

## API overview

All routes below are relative to `http://localhost:5098/api`. Except for registration and login, the routes require a JWT in the `Authorization: Bearer <token>` header unless stated otherwise.

| Area | Endpoints | Access |
| --- | --- | --- |
| Authentication | `POST /auth/login` | Anonymous |
| Prosumer registration | `POST /auth/register/prosumer` | Anonymous |
| Users | `GET/POST/PUT/DELETE /users...` | Backoffice |
| Prosumer management | `GET/PUT /prosumers...` | Role-dependent; Backoffice and GridOperator can list, Prosumer is limited to their own profile |
| Nodes | `GET /nodes`, `GET /nodes/{id}`, `GET /nodes/nearby` | Any authenticated user |
| Node administration | `POST /nodes`, `PUT /nodes/{id}`, `PUT /nodes/{id}/deactivate` | Backoffice |
| Reservations | `GET/POST /reservations...` | Prosumer, Backoffice, GridOperator |
| Reservation approval | `PUT /reservations/{id}/approve` | Backoffice, GridOperator |
| Reservation QR | `POST /reservations/{id}/qr` | Prosumer, Backoffice |
| QR verification | `POST /reservations/verify-qr`, `PUT /reservations/{id}/finalize` | GridOperator |
| Booking slots | `GET /slots...` | Prosumer, Backoffice, GridOperator |
| Slot administration | `PUT /slots/{id}/status` | Backoffice, GridOperator |
| Operator dashboard | `GET /operator/dashboard`, `GET /operator/stations` | GridOperator, Backoffice |

The complete request collection is available at [docs/smart-solar-microgrid-trading-system API.postman_collection.json](docs/smart-solar-microgrid-trading-system%20API.postman_collection.json).

## MongoDB collections

The API uses these collections in the configured database:

- `Users`: Backoffice, Grid Operator, and Prosumer accounts.
- `SolarStationInfo`: microgrid nodes/stations.
- `EnergyBookingSlots`: time-window capacity and battery-slot availability.
- `EnergyReservation`: reservations, approval data, QR-token data, and transfer completion data.

## Development notes

- API timestamps are stored and processed as UTC. The operator dashboard defines “today” using Sri Lankan time, UTC+05:30.
- Node proximity filtering currently loads active nodes and calculates Haversine distance in application memory; it does not use a MongoDB geospatial index.
- The API enables `AllowAnyOrigin`, `AllowAnyMethod`, and `AllowAnyHeader` CORS settings for development. Restrict this policy before production deployment.
- The repository contains only example unit/instrumentation test templates for the Android app; no comprehensive automated test suite is documented here.

## Repository layout

```text
.
├── api/       ASP.NET Core Web API, MongoDB models, DTOs, and controllers
├── mobile/    Kotlin Android application and Gradle build files
├── web/       Static Bootstrap web portal
└── docs/      Postman API collection
```