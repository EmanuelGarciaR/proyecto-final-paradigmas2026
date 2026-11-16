package com.chivasrumberas.domain.errors;

public class DomainException extends RuntimeException {
    private final String errorType;
    private final int httpStatus;

    public DomainException(String errorType, int httpStatus, String message) {
        super(message);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }

    public String getErrorType() {
        return errorType;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static DomainException validation(String message) {
        return new DomainException("ValidationError", 400, message);
    }

    public static DomainException invalidCredentials() {
        return new DomainException("InvalidCredentialsError", 401, "Email o contraseña incorrectos.");
    }

    public static DomainException invalidToken() {
        return new DomainException("InvalidTokenError", 401, "Token inválido o expirado.");
    }

    public static DomainException unauthorized() {
        return new DomainException("UnauthorizedError", 403, "No tienes permisos para realizar esta acción.");
    }

    public static DomainException chivaNotFound(String chivaId) {
        return new DomainException("ChivaNotFoundError", 404, "Chiva no encontrada.");
    }

    public static DomainException bookingNotFound(String bookingId) {
        String msg = (bookingId != null && !bookingId.isBlank())
                ? "La reserva con ID \"" + bookingId + "\" no fue encontrada."
                : "No se encontró ninguna reserva con los datos proporcionados.";
        return new DomainException("BookingNotFoundError", 404, msg);
    }

    public static DomainException travelNotFound(String travelId) {
        return new DomainException("TravelNotFoundError", 404, "El recorrido con ID \"" + travelId + "\" no fue encontrado.");
    }

    public static DomainException chivaNotAvailable(String chivaId) {
        return new DomainException("ChivaNotAvailableError", 409, "La chiva con ID \"" + chivaId + "\" no está disponible para reservas.");
    }

    public static DomainException bookingConflict(String chivaId, String date, String startTime) {
        return new DomainException(
                "BookingConflictError",
                409,
                "La chiva \"" + chivaId + "\" ya tiene una reserva el " + date + " a las " + startTime + ". Por favor elige otro horario."
        );
    }

    public static DomainException chivaHasActiveBookings(String chivaId) {
        return new DomainException(
                "ChivaHasActiveBookingsError",
                409,
                "No se puede retirar la chiva \"" + chivaId + "\" porque tiene reservas futuras pendientes o confirmadas."
        );
    }

    public static DomainException invalidBookingStatusTransition(String from, String to) {
        return new DomainException(
                "InvalidBookingStatusTransitionError",
                422,
                "No se puede cambiar el estado de \"" + from + "\" a \"" + to + "\"."
        );
    }

    public static DomainException tooManyRequests(String message) {
        return new DomainException("TooManyRequestsError", 429, message);
    }

    public static DomainException internalError(String message) {
        return new DomainException("InternalServerError", 500, message);
    }
}
