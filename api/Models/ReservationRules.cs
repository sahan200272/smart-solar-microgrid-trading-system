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

    public static bool HasSufficientSlotCapacity(EnergyBookingSlot slot, double requestedKWh)
    {
        if (slot.Status == "Closed") return false;
        if (slot.AvailableSlotCount <= 0) return false;
        if (requestedKWh > 0 && slot.AvailableCapacityKWh < requestedKWh) return false;
        return true;
    }
}
