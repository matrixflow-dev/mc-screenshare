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
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Keeps one PowerShell process alive and sends it one request per line: {@code list}, {@code display <n>} or
 * {@code window <handle>}. It writes the result to a temp file and answers {@code ok} or {@code err <message>}.
 * Starting PowerShell takes about a second, so spawning it per frame would cap us well below 1 fps.
 * Results go through a file rather than stdout so window titles survive the console code page.
 */
final class WindowsCapturer implements ScreenCapturer {
    private static final String SCRIPT = """
            # Progress records are written to stderr as CLIXML when output is redirected.
            $ProgressPreference = 'SilentlyContinue'
            Add-Type -AssemblyName System.Windows.Forms,System.Drawing
            Add-Type -TypeDefinition @'
            using System;
            using System.Collections.Generic;
            using System.Runtime.InteropServices;
            using System.Text;
            public static class SS {
              delegate bool EnumProc(IntPtr h, IntPtr l);
              [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
              [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
              [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
              [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int index);
              [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
              [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
              [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out int value, int size);
              [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
              [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
              [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
              [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
              [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
              [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

              // Top-level windows a user would recognise: visible, unowned, not tool windows, not cloaked, with a title.
              public static List<string> Windows() {
                var list = new List<string>();
                EnumWindows((h, l) => {
                  int cloaked;
                  if (IsWindowVisible(h) && GetWindow(h, 4) == IntPtr.Zero && (GetWindowLong(h, -20) & 0x80) == 0
                      && GetWindowTextLength(h) > 0
                      && !(DwmGetWindowAttribute(h, 14, out cloaked, 4) == 0 && cloaked != 0)) {
                    var s = new StringBuilder(GetWindowTextLength(h) + 1);
                    GetWindowText(h, s, s.Capacity);
                    list.Add(h.ToInt64() + "\\t" + s.ToString().Replace('\\t', ' '));
                  }
                  return true;
                }, IntPtr.Zero);
                return list;
              }
            }
            '@
            # Without this, scaled monitors report and copy only part of their pixels.
            [void][SS]::SetProcessDPIAware()
            $utf8 = New-Object System.Text.UTF8Encoding $false
            while (($line = [Console]::In.ReadLine()) -ne $null) {
              try {
                $kind, $id = $line -split ' ', 2
                if ($kind -eq 'list') {
                  $lines = @()
                  $screens = [System.Windows.Forms.Screen]::AllScreens
                  for ($i = 0; $i -lt $screens.Length; $i++) {
                    $b = $screens[$i].Bounds
                    $lines += "display`t$($i + 1)`t$($b.Width)x$($b.Height)"
                  }
                  foreach ($w in [SS]::Windows()) { $lines += "window`t$w" }
                  [System.IO.File]::WriteAllLines($env:SCREENSHARE_FILE, [string[]]$lines, $utf8)
                } elseif ($kind -eq 'window') {
                  $h = [IntPtr][long]$id
                  if (-not [SS]::IsWindow($h)) { throw 'the window was closed' }
                  if ([SS]::IsIconic($h)) { throw 'the window is minimized' }
                  $r = New-Object SS+RECT
                  [void][SS]::GetWindowRect($h, [ref]$r)
                  $bmp = New-Object System.Drawing.Bitmap ($r.Right - $r.Left), ($r.Bottom - $r.Top)
                  $g = [System.Drawing.Graphics]::FromImage($bmp)
                  $hdc = $g.GetHdc()
                  # 2 = PW_RENDERFULLCONTENT, needed for browsers and other GPU-drawn windows.
                  [void][SS]::PrintWindow($h, $hdc, 2)
                  $g.ReleaseHdc($hdc)
                  $bmp.Save($env:SCREENSHARE_FILE, [System.Drawing.Imaging.ImageFormat]::Jpeg)
                  $g.Dispose(); $bmp.Dispose()
                } else {
                  $screens = [System.Windows.Forms.Screen]::AllScreens
                  $i = [int]$id - 1
                  if ($i -lt 0 -or $i -ge $screens.Length) { $i = 0 }
                  $b = $screens[$i].Bounds
                  $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
                  $g = [System.Drawing.Graphics]::FromImage($bmp)
                  $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
                  $bmp.Save($env:SCREENSHARE_FILE, [System.Drawing.Imaging.ImageFormat]::Jpeg)
                  $g.Dispose(); $bmp.Dispose()
                }
                [Console]::Out.WriteLine('ok')
              } catch { [Console]::Out.WriteLine('err ' + $_) }
            }
            """;

    private final Process process;
    private final Writer in;
    private final BufferedReader out;
    private final Path file;
    /** PowerShell's stderr, kept apart from the replies so warnings cannot be mistaken for one. */
    private final Path errors;

    WindowsCapturer() {
        try {
            file = Files.createTempFile("screenshare", ".tmp");
            errors = Files.createTempFile("screenshare-powershell", ".log");
            // Pass the script as -EncodedCommand so stdin stays free for requests.
            String encoded = java.util.Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
            pb.environment().put("SCREENSHARE_FILE", file.toString());
            pb.redirectError(errors.toFile());
            process = pb.start();
            in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not start PowerShell", e);
        }
    }

    @Override
    public synchronized List<CaptureSource> list() throws IOException {
        request("list");
        return CaptureSource.parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Override
    public synchronized BufferedImage capture(CaptureSource source) throws IOException {
        request(source.kind() == CaptureSource.Kind.WINDOW ? "window " + source.id()
                : "display " + (source.kind() == CaptureSource.Kind.DISPLAY ? source.id() : "1"));
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("no image");
        }
        return img;
    }

    private void request(String line) throws IOException {
        in.write(line + "\n");
        in.flush();
        String reply = out.readLine();
        if (reply == null) {
            throw new IOException("PowerShell exited; see " + errors);
        }
        if (!reply.equals("ok")) {
            throw new IOException("capture failed: " + reply);
        }
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
