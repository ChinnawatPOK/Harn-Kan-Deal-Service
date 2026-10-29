package com.harnkan.deal.exception;

/** No participant row matches the payment / user the caller named. Maps to 404. */
public class ParticipantNotFoundException extends RuntimeException {
    public ParticipantNotFoundException(String message) {
        super(message);
    }
}
