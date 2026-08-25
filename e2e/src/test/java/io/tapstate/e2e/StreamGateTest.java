package io.tapstate.e2e;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate has to do two things and they pull against each other: while held, nothing may cross toward
 * the reader; once released, everything the store recorded meanwhile must cross, in the order it was
 * recorded. A gate that dropped what arrived while held would satisfy the first on its own, and one
 * that merely slowed things down would satisfy neither reliably - so both are asserted, and the second
 * names the order rather than the count.
 *
 * <p>The store here is an echo server rather than a database. What is under test is the relay, and a
 * real engine would only make the same assertions slower and flakier.
 *
 * <p>The held assertion is bounded rather than instantaneous - "nothing arrives" is not a state a read
 * can observe, only a state a read can fail to leave. The bound is short because it is paid only on the
 * one assertion that wants it, and this is the one place a duration is the honest instrument: the
 * specification surface above this never sees one.
 */
class StreamGateTest {

    /** Long enough that a working relay would have delivered, short enough to pay on one assertion. */
    private static final int HELD_READ_BOUND_MILLIS = 400;

    @Test
    void deliversNothingWhileHeldAndThenEverythingInTheOrderItWasWritten() throws Exception {
        try (Echo store = new Echo();
                StreamGate gate = StreamGate.inFrontOf(store.host(), store.port());
                Socket reader = new Socket(gate.host(), gate.port())) {
            reader.setSoTimeout(HELD_READ_BOUND_MILLIS);
            PrintWriter toStore = new PrintWriter(
                    new java.io.OutputStreamWriter(reader.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader fromStore = new BufferedReader(
                    new InputStreamReader(reader.getInputStream(), StandardCharsets.UTF_8));

            toStore.println("one");
            assertThat(fromStore.readLine())
                    .as("an open gate is a relay and nothing else")
                    .isEqualTo("one");

            gate.hold();
            toStore.println("two");
            toStore.println("three");

            assertThatThrownBy(fromStore::readLine)
                    .as("held, nothing may reach the reader - a gate that let this through would pass "
                            + "every assertion below as well")
                    .isInstanceOf(SocketTimeoutException.class);

            gate.release();
            assertThat(fromStore.readLine())
                    .as("what the store recorded while the gate was held has to arrive, not be dropped")
                    .isEqualTo("two");
            assertThat(fromStore.readLine())
                    .as("and in the order the store recorded it, which is the whole claim: a gate that "
                            + "reordered would still deliver both")
                    .isEqualTo("three");
        }
    }

    @Test
    void treatsHoldingAndReleasingAsAStateRatherThanACount() throws Exception {
        try (Echo store = new Echo();
                StreamGate gate = StreamGate.inFrontOf(store.host(), store.port());
                Socket reader = new Socket(gate.host(), gate.port())) {
            reader.setSoTimeout(HELD_READ_BOUND_MILLIS);
            PrintWriter toStore = new PrintWriter(
                    new java.io.OutputStreamWriter(reader.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader fromStore = new BufferedReader(
                    new InputStreamReader(reader.getInputStream(), StandardCharsets.UTF_8));

            gate.release();
            assertThat(gate.isHeld()).as("releasing a gate nobody held leaves it open").isFalse();

            gate.hold();
            gate.hold();
            assertThat(gate.isHeld()).isTrue();

            toStore.println("held");
            assertThatThrownBy(fromStore::readLine).isInstanceOf(SocketTimeoutException.class);

            // One release, after two holds: a counting gate would still be holding here.
            gate.release();
            assertThat(fromStore.readLine()).isEqualTo("held");
        }
    }

    /** A store stand-in: whatever is written to it comes back, line by line, in order. */
    private static final class Echo implements AutoCloseable {

        private final ServerSocket listener;
        private final Thread serving;
        private volatile boolean closed;

        Echo() throws IOException {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            serving = new Thread(this::serve, "echo-store");
            serving.setDaemon(true);
            serving.start();
        }

        String host() {
            return listener.getInetAddress().getHostAddress();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void serve() {
            while (!closed) {
                try (Socket client = listener.accept();
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter out = new PrintWriter(
                                new java.io.OutputStreamWriter(
                                        client.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        out.println(line);
                    }
                } catch (IOException done) {
                    if (!closed) {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            listener.close();
            serving.interrupt();
        }
    }
}
