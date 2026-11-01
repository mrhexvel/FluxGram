package org.telegram.messenger.plugins.reputation;

import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.math.BigInteger;
import java.net.URL;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

public final class PluginReputationTransportCheck {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);

    public static void main(String[] args) throws Exception {
        queryCodec();
        framingCodec();
        endpoints();
        transport();
    }

    private static void queryCodec() throws Exception {
        PluginReputationQuery withoutKey = new PluginReputationQuery(digest('a'), null);
        byte[] encoded = withoutKey.encode();
        String expected = "464752510000000100000000" + digest('a');

        require(encoded.length == 44);
        require(hex(encoded).equals(expected));
        require(PluginReputationQuery.decode(encoded).equals(withoutKey));

        PluginReputationQuery withKey = new PluginReputationQuery(digest('b'), keyId('c'));
        byte[] encodedWithKey = withKey.encode();

        require(encodedWithKey.length == 76);
        require(hex(encodedWithKey).equals(
                "464752510000000100000001" + digest('b') + digest('c')));
        require(PluginReputationQuery.decode(encodedWithKey).equals(withKey));
        require(PluginReputationQuery.decode(encodedWithKey).hashCode() == withKey.hashCode());

        malformedCodec(encodedWithKey, PluginReputationQuery::decode, 0, 7, 11);
        expectIo(() -> PluginReputationQuery.decode(Arrays.copyOf(encodedWithKey, 43)));
        expectIo(() -> PluginReputationQuery.decode(Arrays.copyOf(encodedWithKey, 77)));
        expectIo(() -> PluginReputationQuery.decode(null));
        expectInvalid(() -> new PluginReputationQuery(digest('A'), null));
        expectInvalid(() -> new PluginReputationQuery(digest('a'), "p256:" + digest('A')));
    }

    private static void framingCodec() throws Exception {
        byte[] response = new byte[] {1, 2, 3};
        byte[] envelope = new byte[] {4, 5};
        PluginReputationTransportResponse framing =
                new PluginReputationTransportResponse(response, envelope);
        response[0] = 9;
        envelope[0] = 9;
        byte[] encoded = framing.encode();

        require(hex(encoded).equals("46475254000000010000000000000003000000020102030405"));
        require(PluginReputationTransportResponse.decode(encoded).equals(framing));
        require(framing.response()[0] == 1);
        require(framing.envelope()[0] == 4);

        byte[] returned = framing.response();
        returned[0] = 8;
        require(framing.response()[0] == 1);

        malformedCodec(encoded, PluginReputationTransportResponse::decode, 0, 7, 11);

        byte[] responseLength = encoded.clone();
        responseLength[15] = 4;
        expectIo(() -> PluginReputationTransportResponse.decode(responseLength));

        byte[] envelopeLength = encoded.clone();
        envelopeLength[19] = 3;
        expectIo(() -> PluginReputationTransportResponse.decode(envelopeLength));
        expectIo(() -> PluginReputationTransportResponse.decode(Arrays.copyOf(encoded, 21)));
        expectIo(() -> PluginReputationTransportResponse.decode(Arrays.copyOf(encoded, 26)));
        expectIo(() -> PluginReputationTransportResponse.decode(null));
        expectInvalid(() -> new PluginReputationTransportResponse(new byte[0], envelope));
        expectInvalid(() -> new PluginReputationTransportResponse(response, new byte[513]));

        byte[] maximumResponse = new byte[128];
        byte[] maximumEnvelope = new byte[512];
        PluginReputationTransportResponse maximum =
                new PluginReputationTransportResponse(maximumResponse, maximumEnvelope);
        require(maximum.encode().length == 660);
        require(PluginReputationTransportResponse.decode(maximum.encode()).equals(maximum));

        byte[] negativeResponseLength = encoded.clone();
        negativeResponseLength[12] = (byte) 0x80;
        expectIo(() -> PluginReputationTransportResponse.decode(negativeResponseLength));

        byte[] negativeEnvelopeLength = encoded.clone();
        negativeEnvelopeLength[16] = (byte) 0x80;
        expectIo(() -> PluginReputationTransportResponse.decode(negativeEnvelopeLength));
    }

    private static void endpoints() throws Exception {
        PluginReputationEndpoint disabled = PluginReputationEndpoint.create("", "");
        require(!disabled.isEnabled());
        expectState(disabled::queryUrl);

        PluginReputationEndpoint endpoint = PluginReputationEndpoint.create(
                "https://reputation.fluxgram.org/",
                keyId('a'));
        require(endpoint.isEnabled());
        require(endpoint.baseUrl.equals("https://reputation.fluxgram.org"));
        require(endpoint.queryUrl().toString().equals(
                "https://reputation.fluxgram.org/v1/plugin-reputation/query"));

        PluginReputationEndpoint port = PluginReputationEndpoint.create(
                "https://reputation.fluxgram.org:443",
                keyId('a'));
        require(port.queryUrl().toString().equals(
                "https://reputation.fluxgram.org:443/v1/plugin-reputation/query"));

        expectInvalid(() -> PluginReputationEndpoint.create("https://example.com", ""));
        expectInvalid(() -> PluginReputationEndpoint.create("", keyId('a')));
        expectInvalid(() -> PluginReputationEndpoint.create("https://example.com", keyId('A')));

        String[] invalid = {
                "http://example.com",
                "HTTPS://example.com",
                "https://Example.com",
                "https://user@example.com",
                "https://example.com?query",
                "https://example.com#fragment",
                "https://example.com:444",
                "https://example.com/path",
                "https://localhost",
                "https://127.0.0.1",
                "https://[::1]",
                "https://example.com.",
                "https://exámple.com",
                "https://xn--exmple-cua.com",
                "https://example..com",
                "https://-example.com",
                "https://example_.com",
                "https://example.com%2f.evil.org"
        };

        for (String value : invalid) {
            expectInvalid(() -> PluginReputationEndpoint.create(value, keyId('a')));
        }
    }

    private static void transport() throws Exception {
        KeyPair authority = keyPair();
        KeyPair otherAuthority = keyPair();
        String authorityKeyId = keyId(authority);
        PluginReputationQuery query = new PluginReputationQuery(digest('a'), keyId('b'));
        PluginReputationResponse response = response(
                query.archiveSha256,
                query.developerKeyId,
                1,
                100,
                200);
        byte[] envelope = envelope(authority, response).encode();
        byte[] framing = new PluginReputationTransportResponse(
                response.encode(),
                envelope).encode();
        PluginReputationEndpoint endpoint = PluginReputationEndpoint.create(
                "https://reputation.example",
                authorityKeyId);

        FakeHttpsURLConnection success = connection(endpoint, framing);
        success.headers.put("Content-Length", Integer.toString(framing.length));
        PluginReputationHttpTransport.Result result = execute(endpoint, success, query, 150);

        require(result.verification.status == PluginReputationVerification.Status.VERIFIED);
        require(success.getURL().toString().equals(endpoint.queryUrl().toString()));
        require(success.getRequestMethod().equals("POST"));
        require(success.getRequestProperty("Content-Type").equals("application/octet-stream"));
        require(success.getRequestProperty("Accept").equals("application/octet-stream"));
        require(success.getRequestProperty("Accept-Encoding").equals("identity"));
        require(success.getRequestProperty("User-Agent").equals(
                PluginReputationHttpTransport.USER_AGENT));
        require(success.fixedLength == query.encode().length);
        require(Arrays.equals(success.output.toByteArray(), query.encode()));
        require(!success.getInstanceFollowRedirects());
        require(success.getConnectTimeout() == PluginReputationHttpTransport.TIMEOUT_MILLIS);
        require(success.getReadTimeout() == PluginReputationHttpTransport.TIMEOUT_MILLIS);
        require(!success.getUseCaches());
        require(success.getDoInput());
        require(success.getDoOutput());
        require(success.disconnected);
        require(!success.errorStreamRead);

        byte[] resultResponse = result.response();
        resultResponse[0] ^= 1;
        require(!Arrays.equals(resultResponse, result.response()));
        byte[] resultEnvelope = result.envelope();
        resultEnvelope[0] ^= 1;
        require(!Arrays.equals(resultEnvelope, result.envelope()));

        FakeHttpsURLConnection absentLength = connection(endpoint, framing);
        execute(endpoint, absentLength, query, 150);

        FakeHttpsURLConnection identityEncoding = connection(endpoint, framing);
        identityEncoding.headers.put("Content-Encoding", "Identity");
        execute(endpoint, identityEncoding, query, 150);

        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Length", "false"));
        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Length", ""));
        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Length", "+" + framing.length));
        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Length", "-1"));
        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Length", framing.length + ", " + framing.length));

        FakeHttpsURLConnection leadingZeros = connection(endpoint, framing);
        leadingZeros.headers.put("Content-Length", "000" + framing.length);
        execute(endpoint, leadingZeros, query, 150);

        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Length", "1025"));
        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Length", Integer.toString(framing.length - 1)));
        FakeHttpsURLConnection exactBound = connection(endpoint, new byte[1024]);
        IOException exactBoundError = transportFailure(endpoint, query, exactBound, connection -> {
        });
        require(exactBound.inputStreamCalls == 1);
        require(!exactBoundError.getMessage().contains("exceeds size limit"));

        FakeHttpsURLConnection overBound = connection(endpoint, new byte[1025]);
        IOException overBoundError = transportFailure(endpoint, query, overBound, connection -> {
        });
        require(overBound.inputStreamCalls == 1);
        require(overBoundError.getMessage().contains("exceeds size limit"));

        for (int status : new int[] {301, 302, 307, 308, 400, 500}) {
            FakeHttpsURLConnection redirect = connection(endpoint, framing);
            redirect.status = status;
            transportFailure(endpoint, query, redirect, connection -> {
            });
            require(!redirect.errorStreamRead);
            require(redirect.inputStreamCalls == 0);
        }

        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Type", "application/json"));
        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Type", "application/octet-stream; charset=binary"));
        transportFailure(endpoint, query, connection(endpoint, framing), connection ->
                connection.headers.put("Content-Encoding", "gzip"));
        transportFailure(endpoint, query, connection(endpoint, new byte[] {1}), connection -> {
        });

        FakeHttpsURLConnection writeFailure = connection(endpoint, framing);
        writeFailure.writeFailure = true;
        transportFailure(endpoint, query, writeFailure, connection -> {
        });
        require(writeFailure.inputStreamCalls == 0);

        FakeHttpsURLConnection readFailure = connection(endpoint, framing);
        readFailure.readFailure = true;
        transportFailure(endpoint, query, readFailure, connection -> {
        });
        require(readFailure.inputStreamCalls == 1);

        byte[] changedResponse = response.encode();
        changedResponse[71] = 2;
        byte[] invalidSignatureFraming = new PluginReputationTransportResponse(
                changedResponse,
                envelope).encode();
        PluginReputationHttpTransport.Result invalidSignature = execute(
                endpoint,
                connection(endpoint, invalidSignatureFraming),
                query,
                150);
        require(invalidSignature.verification.status
                == PluginReputationVerification.Status.INVALID_SIGNATURE);

        PluginReputationEndpoint wrongAuthority = PluginReputationEndpoint.create(
                "https://reputation.example",
                keyId(otherAuthority));
        PluginReputationHttpTransport.Result untrusted = execute(
                wrongAuthority,
                connection(wrongAuthority, framing),
                query,
                150);
        require(untrusted.verification.status
                == PluginReputationVerification.Status.UNTRUSTED_AUTHORITY);

        PluginReputationQuery wrongIdentity = new PluginReputationQuery(digest('c'), keyId('b'));
        PluginReputationHttpTransport.Result mismatch = execute(
                endpoint,
                connection(endpoint, framing),
                wrongIdentity,
                150);
        require(mismatch.verification.status
                == PluginReputationVerification.Status.IDENTITY_MISMATCH);

        PluginReputationHttpTransport.Result future = execute(
                endpoint,
                connection(endpoint, framing),
                query,
                99);
        require(future.verification.status == PluginReputationVerification.Status.NOT_YET_VALID);

        PluginReputationHttpTransport.Result expired = execute(
                endpoint,
                connection(endpoint, framing),
                query,
                200);
        require(expired.verification.status == PluginReputationVerification.Status.EXPIRED);

        FakeClock slowClock = new FakeClock();
        FakeHttpsURLConnection slow = connection(endpoint, framing);
        slow.input = new SlowInputStream(framing, slowClock);
        PluginReputationHttpTransport slowTransport = new PluginReputationHttpTransport(
                endpoint,
                url -> slow,
                slowClock);

        try {
            slowTransport.execute(query, 150);
            throw new AssertionError("Expected request deadline");
        } catch (SocketTimeoutException expected) {
            require(expected.getMessage().contains("deadline"));
            require(slow.disconnected);
            require(slow.inputStreamCalls == 1);
        }

        FakeHttpsURLConnection lateCompletion = connection(endpoint, framing);
        PluginReputationHttpTransport lateCompletionTransport = new PluginReputationHttpTransport(
                endpoint,
                url -> lateCompletion,
                new CompletionDeadlineClock());

        try {
            lateCompletionTransport.execute(query, 150);
            throw new AssertionError("Expected completion deadline");
        } catch (SocketTimeoutException expected) {
            require(expected.getMessage().contains("deadline"));
            require(lateCompletion.disconnected);
            require(lateCompletion.inputStreamCalls == 1);
        }

        expectInvalid(() -> new PluginReputationHttpTransport(
                PluginReputationEndpoint.create("", "")));
    }

    private static PluginReputationHttpTransport.Result execute(
            PluginReputationEndpoint endpoint,
            FakeHttpsURLConnection connection,
            PluginReputationQuery query,
            long nowMillis) throws Exception {
        PluginReputationHttpTransport transport = new PluginReputationHttpTransport(
                endpoint,
                url -> {
                    require(url.equals(endpoint.queryUrl()));
                    return connection;
                });
        PluginReputationHttpTransport.Result result = transport.execute(query, nowMillis);
        require(connection.disconnected);
        return result;
    }

    private static IOException transportFailure(
            PluginReputationEndpoint endpoint,
            PluginReputationQuery query,
            FakeHttpsURLConnection connection,
            ConnectionMutation mutation) throws Exception {
        mutation.apply(connection);

        try {
            execute(endpoint, connection, query, 150);
            throw new AssertionError("Expected transport failure");
        } catch (IOException expected) {
            require(connection.disconnected);
            require(!connection.errorStreamRead);
            return expected;
        }
    }

    private static FakeHttpsURLConnection connection(
            PluginReputationEndpoint endpoint,
            byte[] body) {
        FakeHttpsURLConnection connection = new FakeHttpsURLConnection(endpoint.queryUrl(), body);
        connection.headers.put("Content-Type", "application/octet-stream");
        return connection;
    }

    private static PluginReputationResponse response(
            String archiveSha256,
            String developerKeyId,
            long revision,
            long issuedAtMillis,
            long expiresAtMillis) {
        return new PluginReputationResponse(
                archiveSha256,
                developerKeyId,
                revision,
                issuedAtMillis,
                expiresAtMillis,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                developerKeyId == null
                        ? PluginReputationResponse.KeyStatus.NOT_APPLICABLE
                        : PluginReputationResponse.KeyStatus.CLEAR);
    }

    private static PluginSignatureEnvelope envelope(
            KeyPair key,
            PluginReputationResponse response) throws Exception {
        byte[] message = PluginReputationVerifier.canonicalMessage(
                response,
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                key.getPublic().getEncoded());
        return new PluginSignatureEnvelope(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                key.getPublic().getEncoded(),
                sign(key, message));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String keyId(KeyPair key) throws Exception {
        return "p256:" + hex(MessageDigest.getInstance("SHA-256").digest(
                key.getPublic().getEncoded()));
    }

    private static byte[] sign(KeyPair key, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(message);
        return lowS(signer.sign());
    }

    private static byte[] lowS(byte[] signature) {
        BigInteger[] values = decodeSignature(signature);
        BigInteger s = values[1];

        if (s.compareTo(P256_ORDER.shiftRight(1)) > 0) {
            s = P256_ORDER.subtract(s);
        }

        return encodeSignature(values[0], s);
    }

    private static BigInteger[] decodeSignature(byte[] signature) {
        int rLength = signature[3] & 255;
        int rStart = 4;
        int sOffset = rStart + rLength;
        int sLength = signature[sOffset + 1] & 255;
        int sStart = sOffset + 2;
        return new BigInteger[] {
                new BigInteger(1, Arrays.copyOfRange(signature, rStart, rStart + rLength)),
                new BigInteger(1, Arrays.copyOfRange(signature, sStart, sStart + sLength))
        };
    }

    private static byte[] encodeSignature(BigInteger r, BigInteger s) {
        byte[] rBytes = positiveInteger(r);
        byte[] sBytes = positiveInteger(s);
        byte[] result = new byte[6 + rBytes.length + sBytes.length];
        int offset = 0;
        result[offset++] = 0x30;
        result[offset++] = (byte) (result.length - 2);
        result[offset++] = 0x02;
        result[offset++] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, result, offset, rBytes.length);
        offset += rBytes.length;
        result[offset++] = 0x02;
        result[offset++] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, result, offset, sBytes.length);
        return result;
    }

    private static byte[] positiveInteger(BigInteger value) {
        byte[] encoded = value.toByteArray();

        if (encoded[0] == 0 && encoded.length > 1 && (encoded[1] & 128) == 0) {
            return Arrays.copyOfRange(encoded, 1, encoded.length);
        }
        if ((encoded[0] & 128) != 0) {
            byte[] prefixed = new byte[encoded.length + 1];
            System.arraycopy(encoded, 0, prefixed, 1, encoded.length);
            return prefixed;
        }

        return encoded;
    }

    private static void malformedCodec(
            byte[] valid,
            Decoder decoder,
            int magicOffset,
            int versionOffset,
            int flagsOffset) throws Exception {
        byte[] magic = valid.clone();
        magic[magicOffset] ^= 1;
        expectIo(() -> decoder.decode(magic));

        byte[] oldVersion = valid.clone();
        oldVersion[versionOffset] = 0;
        expectIo(() -> decoder.decode(oldVersion));

        byte[] futureVersion = valid.clone();
        futureVersion[versionOffset] = 2;
        expectIo(() -> decoder.decode(futureVersion));

        byte[] flags = valid.clone();
        flags[flagsOffset] = 2;
        expectIo(() -> decoder.decode(flags));
    }

    private static String digest(char value) {
        char[] result = new char[64];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static String keyId(char value) {
        return "p256:" + digest(value);
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];

        for (int i = 0; i < value.length; i++) {
            result[i * 2] = alphabet[(value[i] >>> 4) & 15];
            result[i * 2 + 1] = alphabet[value[i] & 15];
        }

        return new String(result);
    }

    private static void expectIo(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
        }
    }

    private static void expectInvalid(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void expectState(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface Decoder {
        Object decode(byte[] value) throws Exception;
    }

    private interface ConnectionMutation {
        void apply(FakeHttpsURLConnection connection) throws Exception;
    }

    private static final class FakeHttpsURLConnection extends HttpsURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Map<String, String> headers = new HashMap<>();
        final byte[] body;

        int status = 200;
        int fixedLength = -1;
        int inputStreamCalls;
        boolean disconnected;
        boolean errorStreamRead;
        boolean writeFailure;
        boolean readFailure;
        InputStream input;

        FakeHttpsURLConnection(URL url, byte[] body) {
            super(url);
            this.body = body.clone();
        }

        @Override
        public void setFixedLengthStreamingMode(int contentLength) {
            super.setFixedLengthStreamingMode(contentLength);
            fixedLength = contentLength;
        }

        @Override
        public OutputStream getOutputStream() {
            if (writeFailure) {
                return new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("write failure");
                    }

                    @Override
                    public void write(byte[] value, int offset, int length) throws IOException {
                        throw new IOException("write failure");
                    }
                };
            }

            return output;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public String getHeaderField(String name) {
            return headers.get(name);
        }

        @Override
        public InputStream getInputStream() {
            inputStreamCalls++;

            if (readFailure) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("read failure");
                    }

                    @Override
                    public int read(byte[] value, int offset, int length) throws IOException {
                        throw new IOException("read failure");
                    }
                };
            }

            return input == null ? new ByteArrayInputStream(body) : input;
        }

        @Override
        public InputStream getErrorStream() {
            errorStreamRead = true;
            return new ByteArrayInputStream(new byte[] {9});
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public String getCipherSuite() {
            return "fake";
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() {
            return null;
        }

        @Override
        public Principal getPeerPrincipal() {
            return null;
        }

        @Override
        public Principal getLocalPrincipal() {
            return null;
        }
    }

    private static final class FakeClock implements PluginReputationHttpTransport.Clock {
        private long nowNanos;

        @Override
        public synchronized long nanoTime() {
            return nowNanos;
        }

        private synchronized void advanceMillis(long millis) {
            nowNanos += millis * 1_000_000;
        }
    }

    private static final class CompletionDeadlineClock
            implements PluginReputationHttpTransport.Clock {
        @Override
        public long nanoTime() {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                if ("complete".equals(element.getMethodName())) {
                    return PluginReputationHttpTransport.TIMEOUT_MILLIS * 1_000_000L;
                }
            }

            return 0;
        }
    }

    private static final class SlowInputStream extends InputStream {
        private final byte[] value;
        private final FakeClock clock;

        private int offset;

        private SlowInputStream(byte[] value, FakeClock clock) {
            this.value = value;
            this.clock = clock;
        }

        @Override
        public int read() {
            if (offset == value.length) {
                return -1;
            }

            clock.advanceMillis(6_000);
            return value[offset++] & 255;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (offset == value.length) {
                return -1;
            }

            clock.advanceMillis(6_000);
            target[targetOffset] = value[offset++];
            return 1;
        }
    }
}
