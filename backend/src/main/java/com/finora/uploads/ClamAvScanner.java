package com.finora.uploads;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * {@link MalwareScanner} over clamd's {@code INSTREAM} command.
 *
 * <p>The protocol is small enough to speak directly rather than through a client library:
 * {@code zINSTREAM\0}, then the file as chunks of a 4-byte big-endian length followed by that
 * many bytes, then a zero-length chunk; clamd answers one line, {@code stream: OK},
 * {@code stream: <signature> FOUND}, or {@code ... ERROR}, terminated by {@code \0}. The stream
 * is what makes this fit: the upload never touches the scanner's disk, and clamd's own
 * {@code StreamMaxLength} (25 MB by default) sits above this app's 10 MB upload cap.
 *
 * <p>Every failure -- refused connection, timeout, a reply this does not recognise, the
 * connection dropping mid-stream when clamd rejects an oversized stream -- is reported as
 * {@link MalwareScanner.ScanResult.Status#UNAVAILABLE}. Whether that refuses the upload or waves
 * it through is {@link UploadScanGate}'s decision, not this class's.
 *
 * <p>No connection pooling. clamd handles one command per connection and scanning is bounded
 * by the import concurrency limiter upstream (six at a time), so a socket per scan is the
 * simplest thing that is also correct.
 */
public class ClamAvScanner implements MalwareScanner {

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_REPLY_LENGTH = 1024;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ClamAvScanner(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public ScanResult scan(InputStream content, long size) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(INSTREAM);
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = content.read(buffer)) > 0) {
                out.write(ByteBuffer.allocate(4).putInt(read).array());
                out.write(buffer, 0, read);
            }
            out.write(new byte[4]);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (IOException e) {
            return ScanResult.unavailable("clamd at " + host + ":" + port + ": " + e);
        }
    }

    @Override
    public String describe() {
        return "ClamAV (clamd INSTREAM at " + host + ":" + port + ")";
    }

    /** Reads up to the terminating NUL or end of stream, bounded so a misbehaving peer cannot
     *  make this read forever. */
    private static String readReply(InputStream in) throws IOException {
        StringBuilder reply = new StringBuilder();
        int b;
        while (reply.length() < MAX_REPLY_LENGTH && (b = in.read()) != -1 && b != 0) {
            reply.append((char) b);
        }
        return reply.toString().trim();
    }

    /** Package-private for the unit test: the reply grammar is the whole contract with clamd. */
    static ScanResult parse(String reply) {
        if (reply.endsWith(" OK")) {
            return ScanResult.clean();
        }
        if (reply.endsWith(" FOUND")) {
            String signature = reply.substring(0, reply.length() - " FOUND".length());
            int colon = signature.indexOf(": ");
            if (colon >= 0) {
                signature = signature.substring(colon + 2);
            }
            return ScanResult.infected(signature.trim());
        }
        return ScanResult.unavailable(reply.isEmpty() ? "empty reply from clamd" : "unexpected reply from clamd: " + reply);
    }
}
