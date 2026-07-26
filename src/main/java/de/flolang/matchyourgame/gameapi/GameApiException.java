package de.flolang.matchyourgame.gameapi;

public final class GameApiException extends Exception {
    public GameApiException(String message) {
        super(message);
    }

    public GameApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
