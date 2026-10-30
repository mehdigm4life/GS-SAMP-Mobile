package com.downloader;

/** Failure description handed to {@link OnDownloadListener#onError(Error)}. */
public class Error {
    private final String message;

    public Error() {
        this("Unknown error");
    }

    public Error(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "Error{" + message + '}';
    }
}
