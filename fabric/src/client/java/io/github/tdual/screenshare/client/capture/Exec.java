package io.github.tdual.screenshare.client.capture;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a helper command and returns what it printed. */
final class Exec {
    private Exec() {}

    static byte[] run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(cmd.getFirst() + " timed out");
            }
            if (p.exitValue() != 0) {
                throw new IOException(cmd.getFirst() + " exited with " + p.exitValue());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
