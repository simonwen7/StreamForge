package com.streamforge.worker;

/**
 * A record that cannot become a processing receipt.
 * The message is safe to log and to copy onto the dead-letter topic.
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }
}
