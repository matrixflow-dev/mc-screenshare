package io.github.tdual.screenshare.client.capture;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalTime;

/** Development only: draws a moving test pattern instead of grabbing the desktop. */
final class TestPatternCapturer implements ScreenCapturer {
    private int frame;

    @Override
    public BufferedImage capture() {
        BufferedImage img = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Color[] bars = {Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE};
        for (int i = 0; i < bars.length; i++) {
            g.setColor(bars[i]);
            g.fillRect(i * 1600 / bars.length, 0, 1600 / bars.length + 1, 900);
        }
        g.setColor(Color.BLACK);
        g.fillRect(0, 330, 1600, 240);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 120));
        g.drawString("frame " + frame++ + "  " + LocalTime.now().withNano(0), 60, 500);
        g.dispose();
        return img;
    }
}
