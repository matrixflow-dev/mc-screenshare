package io.github.tdual.screenshare.client.capture;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Keeps one PowerShell process alive and asks it for a screenshot per frame.
 * Starting PowerShell takes about a second, so spawning it per frame would cap us well below 1 fps.
 */
final class WindowsCapturer implements ScreenCapturer {
    private static final String SCRIPT = String.join("\n",
            "Add-Type -AssemblyName System.Windows.Forms,System.Drawing",
            "$screens = [System.Windows.Forms.Screen]::AllScreens",
            "while (($line = [Console]::In.ReadLine()) -ne $null) {",
            "  try {",
            "    $i = [int]$env:SCREENSHARE_DISPLAY - 1",
            "    if ($i -lt 0 -or $i -ge $screens.Length) { $i = 0 }",
            "    $b = $screens[$i].Bounds",
            "    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height",
            "    $g = [System.Drawing.Graphics]::FromImage($bmp)",
            "    $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)",
            "    $bmp.Save($line, [System.Drawing.Imaging.ImageFormat]::Jpeg)",
            "    $g.Dispose(); $bmp.Dispose()",
            "    [Console]::Out.WriteLine('ok')",
            "  } catch { [Console]::Out.WriteLine('err ' + $_) }",
            "}");

    private final Process process;
    private final Writer in;
    private final BufferedReader out;
    private final Path file;

    WindowsCapturer(int display) {
        try {
            file = Files.createTempFile("screenshare", ".jpg");
            // Pass the script as -EncodedCommand so stdin stays free for frame requests.
            String encoded = java.util.Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
            pb.environment().put("SCREENSHARE_DISPLAY", Integer.toString(display));
            pb.redirectErrorStream(true);
            process = pb.start();
            in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not start PowerShell", e);
        }
    }

    @Override
    public synchronized BufferedImage capture() throws IOException {
        in.write(file.toString() + "\n");
        in.flush();
        String reply = out.readLine();
        if (reply == null) {
            throw new IOException("PowerShell exited");
        }
        if (!reply.equals("ok")) {
            throw new IOException("capture failed: " + reply);
        }
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("no image");
        }
        return img;
    }

    @Override
    public void close() {
        process.destroy();
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
