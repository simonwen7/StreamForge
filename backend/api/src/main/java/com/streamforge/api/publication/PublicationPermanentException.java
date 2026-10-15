package com.streamforge.api.publication;

/**
 * A publication defect that will not succeed on retry until the stored event is corrected.
 * Transient broker and database failures must not use this type.
 */
public class PublicationPermanentException extends RuntimeException {

    private final String errorCode;

    public PublicationPermanentException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
