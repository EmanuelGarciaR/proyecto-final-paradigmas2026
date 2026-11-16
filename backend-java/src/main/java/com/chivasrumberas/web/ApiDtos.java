package com.chivasrumberas.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public final class ApiDtos {

    private ApiDtos() {}

    public record ErrorDetail(String type, String message) {}
    public record ErrorResponse(ErrorDetail error) {
        public static ErrorResponse of(String type, String message) {
            return new ErrorResponse(new ErrorDetail(type, message));
        }
    }

    public record HealthResponse(String status, String timestamp) {}

    public record LoginDto(String email, String password) {}
    public record AuthUserDto(String id, String email, String role) {}
    public record AuthResponseDto(String accessToken, AuthUserDto user) {}

    public record ChivaResponseDto(
            String id,
            String name,
            String description,
            int capacity,
            List<String> imageUrls,
            double pricePerHour,
            String status,
            List<String> features
    ) {}

    public record OccupiedSlotDto(
            String startTime,
            String endTime,
            double durationHours
    ) {}

    public record AvailabilityResponseDto(
            String chivaId,
            String date,
            List<OccupiedSlotDto> occupiedSlots,
            boolean isFullyBooked
    ) {}

    public record TravelResponseDto(
            String id,
            String name,
            String description,
            double durationHours,
            List<String> highlights
    ) {}

    public record CreateBookingDto(
            String chivaId,
            String travelId,
            String customerName,
            String customerEmail,
            String customerPhone,
            Integer guestCount,
            String eventDate,
            String startTime,
            Double durationHours,
            String notes
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BookingResponseDto(
            String id,
            String chivaId,
            String chivaName,
            String travelId,
            String travelName,
            String customerName,
            String customerEmail,
            String customerPhone,
            int guestCount,
            String eventDate,
            String startTime,
            String endTime,
            double durationHours,
            double totalPrice,
            String status,
            String notes,
            String calendarSyncStatus,
            String createdAt
    ) {}

    public record PaginationMetaDto(
            int page,
            int limit,
            int total,
            int totalPages
    ) {}

    public record PaginatedBookingsResponseDto(
            List<BookingResponseDto> data,
            PaginationMetaDto meta
    ) {}

    public record TrackBookingResponseDto(
            String id,
            String code,
            String status,
            String createdAt,
            String chivaId,
            String chivaName,
            String travelId,
            String travelName,
            String eventDate,
            String startTime,
            String endTime,
            double durationHours,
            int guestCount,
            double totalPrice
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CalendarSlotDto(
            String bookingId,
            String startTime,
            String endTime,
            double durationHours,
            String customerName,
            String travelName,
            int guestCount,
            Boolean endsNextDay
    ) {}

    public record CalendarDayChivaDto(
            String chivaId,
            String chivaName,
            List<CalendarSlotDto> slots
    ) {}

    public record CalendarDayDto(
            String date,
            int chivasCount,
            List<CalendarDayChivaDto> chivas
    ) {}

    public record CalendarChivaSummaryDto(
            String id,
            String name
    ) {}

    public record BookingsCalendarResponseDto(
            String month,
            List<CalendarChivaSummaryDto> chivas,
            List<CalendarDayDto> days
    ) {}

    public record UploadSignatureDto(
            String cloudName,
            String apiKey,
            long timestamp,
            String signature,
            String folder,
            List<String> allowedFormats,
            long maxFileSize
    ) {}
}
