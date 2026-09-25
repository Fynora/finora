package com.finora.uploads;

import com.finora.uploads.MalwareScanner.ScanResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clamd INSTREAM protocol against a fake clamd on a local socket: what was sent is read back
 * byte-for-byte on the server side, and each reply shape maps to the verdict the gate needs.
 * A real ClamAV container would prove the same protocol at a hundred times the cost; the reply
 * grammar is documented and small, and the fake asserts the framing exactly.
 */
class ClamAvScannerTest {

    /** The EICAR test string, split so this source file is not itself flagged by a scanner. */
    private static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!"
            + "$H+H*").getBytes(StandardCharsets.US_ASCII);

    /** A single-connection fake clamd. Reads the command and the framed chunks, hands the
     *  reassembled payload to {@code verdict}, writes its reply, and records what it saw. */
    private record FakeClamd(ServerSocket server, CompletableFuture<byte[]> received) implements AutoCloseable {

        static FakeClamd start(Function<byte[], String> verdict) throws IOException {
            ServerSocket server = new ServerSocket(0);
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream in = new DataInputStream(socket.getInputStream());
                     OutputStream out = socket.getOutputStream()) {
                    byte[] command = in.readNBytes("zINSTREAM\0".length());
                    if (!"zINSTREAM\0".equals(new String(command, StandardCharsets.US_ASCII))) {
                        received.completeExceptionally(new AssertionError("wrong command: "
                                + new String(command, StandardCharsets.US_ASCII)));
                        return;
                    }
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    while (true) {
                        int length = in.readInt();
                        if (length == 0) break;
                        payload.write(in.readNBytes(length));
                    }
                    byte[] bytes = payload.toByteArray();
                    received.complete(bytes);
                    String reply = verdict.apply(bytes);
                    if (reply != null) {
                        out.write(reply.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                } catch (IOException e) {
                    received.completeExceptionally(e);
                }
            }, "fake-clamd");
            thread.setDaemon(true);
            thread.start();
            return new FakeClamd(server, received);
        }

        ClamAvScanner scanner() {
            return new ClamAvScanner("127.0.0.1", server.getLocalPort(), 1000, 2000);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static InputStream bytes(byte[] content) {
        return new ByteArrayInputStream(content);
    }

    @Test
    void aCleanReplyIsClean_andTheWholeFileWasStreamedInFrames() throws Exception {
        byte[] file = new byte[20_000];
        for (int i = 0; i < file.length; i++) file[i] = (byte) (i % 251);
        try (FakeClamd clamd = FakeClamd.start(bytes -> "stream: OK\0")) {
            ScanResult result = clamd.scanner().scan(bytes(file), file.length);

            assertThat(result.status()).isEqualTo(ScanResult.Status.CLEAN);
            assertThat(clamd.received().get(5, TimeUnit.SECONDS))
                    .as("every byte arrives, in order, across more than one 8 KiB frame")
                    .isEqualTo(file);
        }
    }

    @Test
    void aFoundReplyIsInfected_withTheSignatureExtracted() throws Exception {
        try (FakeClamd clamd = FakeClamd.start(bytes -> "stream: Win.Test.EICAR_HDB-1 FOUND\0")) {
            ScanResult result = clamd.scanner().scan(bytes(EICAR), EICAR.length);

            assertThat(result.status()).isEqualTo(ScanResult.Status.INFECTED);
            assertThat(result.detail()).isEqualTo("Win.Test.EICAR_HDB-1");
        }
    }

    @Test
    void anErrorReplyIsUnavailable_neverClean() throws Exception {
        try (FakeClamd clamd = FakeClamd.start(bytes -> "INSTREAM size limit exceeded. ERROR\0")) {
            ScanResult result = clamd.scanner().scan(bytes(EICAR), EICAR.length);

            assertThat(result.status()).isEqualTo(ScanResult.Status.UNAVAILABLE);
            assertThat(result.detail()).contains("ERROR");
        }
    }

    @Test
    void aClosedConnectionWithNoReplyIsUnavailable() throws Exception {
        try (FakeClamd clamd = FakeClamd.start(bytes -> null)) {
            ScanResult result = clamd.scanner().scan(bytes(EICAR), EICAR.length);

            assertThat(result.status()).isEqualTo(ScanResult.Status.UNAVAILABLE);
        }
    }

    @Test
    void nothingListeningIsUnavailable_notAnException() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", freePort, 500, 500);

        ScanResult result = scanner.scan(bytes(EICAR), EICAR.length);

        assertThat(result.status()).isEqualTo(ScanResult.Status.UNAVAILABLE);
        assertThat(result.detail()).contains("127.0.0.1:" + freePort);
    }

    @Test
    void aDaemonThatNeverAnswersIsUnavailableAfterTheReadTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            // Accepts nothing: the connect succeeds (backlog), the read waits out the 300 ms.
            ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", silent.getLocalPort(), 500, 300);
            long started = System.nanoTime();

            ScanResult result = scanner.scan(bytes(EICAR), EICAR.length);

            assertThat(result.status()).isEqualTo(ScanResult.Status.UNAVAILABLE);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
        }
    }

    @Test
    void replyGrammar() {
        assertThat(ClamAvScanner.parse("stream: OK").status()).isEqualTo(ScanResult.Status.CLEAN);
        assertThat(ClamAvScanner.parse("stream: Eicar-Test-Signature FOUND"))
                .isEqualTo(ScanResult.infected("Eicar-Test-Signature"));
        assertThat(ClamAvScanner.parse("").status()).isEqualTo(ScanResult.Status.UNAVAILABLE);
        assertThat(ClamAvScanner.parse("OK").status())
                .as("a bare OK is not the documented reply; treat anything off-grammar as no answer")
                .isEqualTo(ScanResult.Status.UNAVAILABLE);
        assertThat(ClamAvScanner.parse("stream: FOUND").status())
                .as("FOUND with no signature is still a detection")
                .isEqualTo(ScanResult.Status.INFECTED);
    }
}
