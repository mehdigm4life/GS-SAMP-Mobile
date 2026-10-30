package com.downloader;

/** Timeouts and options applied to every download. */
public class PRDownloaderConfig {
    private final boolean databaseEnabled;
    private final int readTimeout;
    private final int connectTimeout;

    private PRDownloaderConfig(Builder builder) {
        this.databaseEnabled = builder.databaseEnabled;
        this.readTimeout = builder.readTimeout;
        this.connectTimeout = builder.connectTimeout;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public boolean isDatabaseEnabled() {
        return databaseEnabled;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public static class Builder {
        private boolean databaseEnabled = false;
        private int readTimeout = 30000;
        private int connectTimeout = 30000;

        public Builder setDatabaseEnabled(boolean databaseEnabled) {
            this.databaseEnabled = databaseEnabled;
            return this;
        }

        public Builder setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder setConnectTimeout(int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public PRDownloaderConfig build() {
            return new PRDownloaderConfig(this);
        }
    }
}
