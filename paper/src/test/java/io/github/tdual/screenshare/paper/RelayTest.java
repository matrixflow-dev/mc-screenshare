package io.github.tdual.screenshare.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayTest {
    private final Relay.Player alice = new Relay.Player(UUID.randomUUID(), "alice");
    private final Relay.Player bob = new Relay.Player(UUID.randomUUID(), "bob");
    private final Relay.Player carol = new Relay.Player(UUID.randomUUID(), "carol");

    @Test
    void firstChunkStartsShare() {
        Relay r = new Relay(3, 600, 10);
        assertEquals(Relay.FrameResult.STARTED, r.onChunk(alice, 1000, 0));
        assertEquals(Relay.FrameResult.ACCEPTED, r.onChunk(alice, 1000, 10));
    }

    @Test
    void anyoneWatcherDoesNotGetOwnFrames() {
        Relay r = new Relay(3, 600, 10);
        r.watch(alice.id(), "");
        r.watch(bob.id(), "");
        assertEquals(List.of(bob.id()), r.recipients(alice, List.of(alice, bob, carol)));
    }

    @Test
    void namedTargetIsCaseInsensitiveAndAllowsSelf() {
        Relay r = new Relay(3, 600, 10);
        r.watch(alice.id(), "ALICE");
        r.watch(bob.id(), "carol");
        assertEquals(List.of(alice.id()), r.recipients(alice, List.of(alice, bob)));
    }

    @Test
    void sharerLimit() {
        Relay r = new Relay(1, 600, 10);
        r.onChunk(alice, 10, 0);
        assertEquals(Relay.FrameResult.TOO_MANY_SHARERS, r.onChunk(bob, 10, 0));
        r.stop(alice.id());
        assertEquals(Relay.FrameResult.STARTED, r.onChunk(bob, 10, 0));
    }

    @Test
    void rateLimitRefillsOverTime() {
        Relay r = new Relay(3, 100, 10); // 100 KB/s, burst 200 KB
        r.onChunk(alice, 200 * 1024, 0);
        assertEquals(Relay.FrameResult.RATE_LIMITED, r.onChunk(alice, 30_000, 0));
        assertEquals(Relay.FrameResult.ACCEPTED, r.onChunk(alice, 30_000, 1000));
    }

    @Test
    void idleShareExpires() {
        Relay r = new Relay(3, 600, 10);
        r.onChunk(alice, 10, 0);
        assertEquals(List.of(), r.expire(10_000));
        assertEquals(List.of(alice), r.expire(10_001));
        assertNull(r.stop(alice.id()));
    }

    @Test
    void leaveEndsShareAndWatch() {
        Relay r = new Relay(3, 600, 10);
        r.onChunk(alice, 10, 0);
        r.watch(alice.id(), "");
        assertEquals("alice", r.leave(alice.id()));
        assertEquals(0, r.watcherCount());
    }

    @Test
    void chunkValidation() {
        assertEquals(3, Wire.validateChunk(chunk(0, 1, 1280, 720, 3, 3)));
        assertEquals(-1, Wire.validateChunk(chunk(1, 1, 1280, 720, 3, 3)), "index out of range");
        assertEquals(-1, Wire.validateChunk(chunk(0, 1, 1280, 720, 5, 3)), "length mismatch");
        assertEquals(-1, Wire.validateChunk(chunk(0, 1, 0, 720, 3, 3)), "zero width");
        assertEquals(-1, Wire.validateChunk(new byte[5]), "truncated");
    }

    @Test
    void controlParsing() {
        ByteBuffer b = ByteBuffer.allocate(1 + 2 + 3);
        b.put((byte) Wire.OP_WATCH).putShort((short) 3).put("bob".getBytes());
        Wire.Control c = Wire.control(b.array());
        assertEquals(Wire.OP_WATCH, c.op());
        assertEquals("bob", c.target());
    }

    private static byte[] chunk(int index, int count, int w, int h, int declaredLen, int actualLen) {
        ByteBuffer b = ByteBuffer.allocate(4 + 2 * 4 + 4 + actualLen);
        b.putInt(7).putShort((short) index).putShort((short) count).putShort((short) w).putShort((short) h);
        b.putInt(declaredLen).put(new byte[actualLen]);
        return b.array();
    }
}
