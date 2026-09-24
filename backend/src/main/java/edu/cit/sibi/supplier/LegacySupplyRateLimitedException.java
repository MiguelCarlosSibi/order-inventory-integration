package edu.cit.sibi.supplier;

class LegacySupplyRateLimitedException extends LegacySupplyUnavailableException {
    LegacySupplyRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}