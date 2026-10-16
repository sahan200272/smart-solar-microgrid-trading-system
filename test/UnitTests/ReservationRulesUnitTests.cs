using SolarMicrogrid.Api.Models;
using Xunit;

namespace SolarMicrogrid.Tests.UnitTests;

public class ReservationRulesUnitTests
{
    private readonly DateTime _now = new(2026, 10, 16, 10, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void IsWithinBookingWindow_WhenSlotIsInPast_ReturnsFalse()
    {
        var pastTime = _now.AddHours(-1);
        Assert.False(ReservationRules.IsWithinBookingWindow(pastTime, _now));
    }

    [Fact]
    public void IsWithinBookingWindow_WhenSlotIsExactlyNow_ReturnsFalse()
    {
        Assert.False(ReservationRules.IsWithinBookingWindow(_now, _now));
    }

    [Fact]
    public void IsWithinBookingWindow_WhenSlotIsWithinSevenDays_ReturnsTrue()
    {
        var futureTime = _now.AddDays(3);
        Assert.True(ReservationRules.IsWithinBookingWindow(futureTime, _now));
    }

    [Fact]
    public void IsWithinBookingWindow_WhenSlotIsExactlySevenDaysAhead_ReturnsTrue()
    {
        var exactBoundary = _now.AddDays(7);
        Assert.True(ReservationRules.IsWithinBookingWindow(exactBoundary, _now));
    }

    [Fact]
    public void IsWithinBookingWindow_WhenSlotIsMoreThanSevenDaysAhead_ReturnsFalse()
    {
        var tooFar = _now.AddDays(7).AddMinutes(1);
        Assert.False(ReservationRules.IsWithinBookingWindow(tooFar, _now));
    }

    [Fact]
    public void HasEnoughNotice_WhenLessThanTwelveHours_ReturnsFalse()
    {
        var elevenHoursAhead = _now.AddHours(11).AddMinutes(59);
        Assert.False(ReservationRules.HasEnoughNotice(elevenHoursAhead, _now));
    }

    [Fact]
    public void HasEnoughNotice_WhenExactlyTwelveHours_ReturnsTrue()
    {
        var twelveHoursAhead = _now.AddHours(12);
        Assert.True(ReservationRules.HasEnoughNotice(twelveHoursAhead, _now));
    }

    [Fact]
    public void HasEnoughNotice_WhenMoreThanTwelveHours_ReturnsTrue()
    {
        var twentyFourHoursAhead = _now.AddHours(24);
        Assert.True(ReservationRules.HasEnoughNotice(twentyFourHoursAhead, _now));
    }

    [Fact]
    public void HasSufficientSlotCapacity_WhenSlotIsClosed_ReturnsFalse()
    {
        var slot = new EnergyBookingSlot
        {
            Status = "Closed",
            AvailableSlotCount = 4,
            AvailableCapacityKWh = 100.0
        };

        Assert.False(ReservationRules.HasSufficientSlotCapacity(slot, 10.0));
    }

    [Fact]
    public void HasSufficientSlotCapacity_WhenNoAvailableSlots_ReturnsFalse()
    {
        var slot = new EnergyBookingSlot
        {
            Status = "Open",
            AvailableSlotCount = 0,
            AvailableCapacityKWh = 100.0
        };

        Assert.False(ReservationRules.HasSufficientSlotCapacity(slot, 10.0));
    }

    [Fact]
    public void HasSufficientSlotCapacity_WhenRequestedEnergyExceedsAvailableCapacity_ReturnsFalse()
    {
        var slot = new EnergyBookingSlot
        {
            Status = "Open",
            AvailableSlotCount = 2,
            AvailableCapacityKWh = 25.0
        };

        Assert.False(ReservationRules.HasSufficientSlotCapacity(slot, 30.0));
    }

    [Fact]
    public void HasSufficientSlotCapacity_WhenSlotIsOpenAndCapacityIsSufficient_ReturnsTrue()
    {
        var slot = new EnergyBookingSlot
        {
            Status = "Open",
            AvailableSlotCount = 2,
            AvailableCapacityKWh = 50.0
        };

        Assert.True(ReservationRules.HasSufficientSlotCapacity(slot, 50.0));
    }
}
