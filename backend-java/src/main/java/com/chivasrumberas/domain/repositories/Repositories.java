package com.chivasrumberas.domain.repositories;

import com.chivasrumberas.domain.models.Models.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Repositories {

    private Repositories() {}

    public interface ChivaRepository {
        List<Chiva> findChivaAll(String status);
        Optional<Chiva> findChivaById(String id);
        Chiva saveChiva(Chiva chiva);
        Chiva updateChivaStatus(String id, String status);
        Chiva updateChiva(String id, Map<String, Object> updates);
    }

    public interface TravelRepository {
        List<Travel> findAllTravelsActive();
        Optional<Travel> findTravelById(String id);
    }

    public interface CustomerRepository {
        Optional<Customer> findByEmail(String email);
        Optional<Customer> findById(String id);
        Customer upsertByEmail(String name, String email, String phone);
    }

    public interface BookingRepository {
        Optional<Booking> findBookingById(String id);
        PaginatedBookingsResult findBookingPaginated(PaginatedBookingsQuery query);
        List<Booking> findConfirmedByDateRange(Instant startDate, Instant endDate);
        List<OccupiedSlotRecord> findOccupiedSlotsByChivaAndDate(String chivaId, Instant date);
        boolean hasConflict(String chivaId, Instant eventDate, String startTime, double durationHours);
        boolean hasFutureActiveBookingsByChiva(String chivaId, Instant fromDate);
        Booking saveBooking(Booking booking);
        Booking updateBookingStatus(String id, String status, Double totalPrice);
        Booking updateBookingCalendarSync(String id, String googleEventId, boolean updateGoogleEventId, String calendarSyncStatus);
    }

    public interface UserRepository {
        Optional<User> findUserByEmail(String email);
        Optional<User> findUserById(String id);
    }
}
