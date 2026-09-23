namespace SolarMicrogrid.Api.Models;

public static class ReservationRules
{
    public static bool IsWithinBookingWindow(DateTime slotStartTime, DateTime now)
    {
        return slotStartTime > now && slotStartTime <= now.AddDays(7);
    }

    public static bool HasEnoughNotice(DateTime slotStartTime, DateTime now)
    {
        return slotStartTime >= now.AddHours(12);
    }
}
