package org.telegram.messenger.plugins.reputation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

public final class PluginReputationHttpTransport {
    public static final int TIMEOUT_MILLIS = 10_000;
    public static final int MAX_HTTP_RESPONSE_BYTES = 1024;
    public static final String USER_AGENT = "FluxGram-Plugin-Reputation/1";

    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    interface Clock {
        long nanoTime();
    }

    public static final class Result {
        public final PluginReputationVerification verification;

        private final byte[] response;
        private final byte[] envelope;

        private Result(
                byte[] response,
                byte[] envelope,
                PluginReputationVerification verification) {
            this.response = response.clone();
            this.envelope = envelope.clone();
            this.verification = verification;
        }

        public byte[] response() {
            return response.clone();
        }

        public byte[] envelope() {
            return envelope.clone();
        }
    }

    private final PluginReputationEndpoint endpoint;
    private final ConnectionFactory connectionFactory;
    private final Clock clock;

    public PluginReputationHttpTransport(PluginReputationEndpoint endpoint) {
        this(endpoint, url -> {
            URLConnection connection = url.openConnection();

            if (!(connection instanceof HttpsURLConnection)) {
                throw new IOException("Plugin reputation endpoint is not HTTPS");
            }

            return (HttpsURLConnection) connection;
        }, System::nanoTime);
    }

    PluginReputationHttpTransport(
            PluginReputationEndpoint endpoint,
            ConnectionFactory connectionFactory) {
        this(endpoint, connectionFactory, System::nanoTime);
    }

    PluginReputationHttpTransport(
            PluginReputationEndpoint endpoint,
            ConnectionFactory connectionFactory,
            Clock clock) {
        if (endpoint == null
                || !endpoint.isEnabled()
                || connectionFactory == null
                || clock == null) {
            throw new IllegalArgumentException("Plugin reputation transport requires an endpoint");
        }

        this.endpoint = endpoint;
        this.connectionFactory = connectionFactory;
        this.clock = clock;
    }

    public Result execute(PluginReputationQuery query, long nowMillis) throws IOException {
        if (query == null || nowMillis < 0) {
            throw new IllegalArgumentException("Invalid plugin reputation transport request");
        }

        byte[] encodedQuery = query.encode();
        HttpsURLConnection connection = connectionFactory.open(endpoint.queryUrl());

        if (connection == null) {
            throw new IOException("Plugin reputation connection factory returned null");
        }

        RequestDeadline deadline = new RequestDeadline(connection, clock);

        try {
            deadline.start();
            configure(connection, encodedQuery.length);
            deadline.check();

            try (OutputStream output = connection.getOutputStream()) {
                output.write(encodedQuery);
            }

            deadline.check();
            int status = connection.getResponseCode();
            deadline.check();

            if (status != HttpsURLConnection.HTTP_OK) {
                throw new IOException("Unexpected plugin reputation HTTP status: " + status);
            }
            if (!"application/octet-stream".equals(connection.getHeaderField("Content-Type"))) {
                throw new IOException("Invalid plugin reputation response content type");
            }

            String contentEncoding = connection.getHeaderField("Content-Encoding");

            if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
                throw new IOException("Invalid plugin reputation response content encoding");
            }

            long contentLength = parseContentLength(connection.getHeaderField("Content-Length"));

            if (contentLength > MAX_HTTP_RESPONSE_BYTES) {
                throw new IOException("Plugin reputation HTTP response exceeds size limit");
            }

            deadline.check();
            byte[] encodedResponse;

            try (InputStream input = connection.getInputStream()) {
                encodedResponse = readBounded(input, deadline);
            }

            deadline.check();
            if (contentLength >= 0 && contentLength != encodedResponse.length) {
                throw new IOException("Plugin reputation HTTP response length mismatch");
            }

            PluginReputationTransportResponse framed =
                    PluginReputationTransportResponse.decode(encodedResponse);
            byte[] response = framed.response();
            byte[] envelope = framed.envelope();
            PluginReputationVerification verification = PluginReputationVerifier.verifyEncoded(
                    response,
                    envelope,
                    endpoint.authorityKeyId,
                    query.archiveSha256,
                    query.developerKeyId,
                    nowMillis);

            deadline.complete();
            return new Result(response, envelope, verification);
        } catch (IOException error) {
            if (deadline.isTimedOut() && !(error instanceof SocketTimeoutException)) {
                SocketTimeoutException timeout = timeoutException();
                timeout.initCause(error);
                throw timeout;
            }

            throw error;
        } finally {
            deadline.close();
            connection.disconnect();
        }
    }

    private static void configure(HttpsURLConnection connection, int queryLength)
            throws IOException {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setFixedLengthStreamingMode(queryLength);
    }

    private static long parseContentLength(String value) throws IOException {
        if (value == null) {
            return -1;
        }
        if (value.isEmpty()) {
            throw new IOException("Invalid plugin reputation response content length");
        }

        long result = 0;

        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);

            if (character < '0' || character > '9') {
                throw new IOException("Invalid plugin reputation response content length");
            }

            result = result * 10 + character - '0';

            if (result > MAX_HTTP_RESPONSE_BYTES) {
                return result;
            }
        }

        return result;
    }

    private static byte[] readBounded(InputStream input, RequestDeadline deadline)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int total = 0;

        while (true) {
            deadline.check();
            int read = input.read(buffer);
            deadline.check();

            if (read == -1) {
                break;
            }
            if (read == 0) {
                continue;
            }

            total += read;

            if (total > MAX_HTTP_RESPONSE_BYTES) {
                throw new IOException("Plugin reputation HTTP response exceeds size limit");
            }

            output.write(buffer, 0, read);
        }

        return output.toByteArray();
    }

    private static SocketTimeoutException timeoutException() {
        return new SocketTimeoutException("Plugin reputation request deadline exceeded");
    }

    private static final class RequestDeadline implements AutoCloseable {
        private static final int ACTIVE = 0;
        private static final int COMPLETED = 1;
        private static final int TIMED_OUT = 2;

        private final HttpsURLConnection connection;
        private final Clock clock;
        private final AtomicInteger state = new AtomicInteger(ACTIVE);

        private long startedNanos;
        private Thread watchdog;

        private RequestDeadline(HttpsURLConnection connection, Clock clock) {
            this.connection = connection;
            this.clock = clock;
        }

        private void start() {
            startedNanos = clock.nanoTime();
            watchdog = new Thread(this::watch, "PluginReputationHttpTimeout");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        private void check() throws SocketTimeoutException {
            if (state.get() == TIMED_OUT || elapsedNanos() >= timeoutNanos()) {
                timeout();
                throw timeoutException();
            }
        }

        private void complete() throws SocketTimeoutException {
            if (!state.compareAndSet(ACTIVE, COMPLETED)) {
                throw timeoutException();
            }
            if (elapsedNanos() >= timeoutNanos()) {
                state.compareAndSet(COMPLETED, TIMED_OUT);
                connection.disconnect();
                throw timeoutException();
            }
        }

        private boolean isTimedOut() {
            return state.get() == TIMED_OUT;
        }

        private void watch() {
            while (state.get() == ACTIVE) {
                long remaining = timeoutNanos() - elapsedNanos();

                if (remaining <= 0) {
                    timeout();
                    return;
                }

                try {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                } catch (InterruptedException error) {
                    return;
                }
            }
        }

        private long elapsedNanos() {
            return clock.nanoTime() - startedNanos;
        }

        private void timeout() {
            if (state.compareAndSet(ACTIVE, TIMED_OUT)) {
                connection.disconnect();
            }
        }

        private static long timeoutNanos() {
            return TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        }

        @Override
        public void close() {
            state.compareAndSet(ACTIVE, COMPLETED);

            Thread thread = watchdog;

            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
