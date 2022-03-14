package com.jpage4500.devicemanager.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.*;

/**
 *
 */
public class EmptyView extends JComponent {
    private static final Logger log = LoggerFactory.getLogger(EmptyView.class);

    private Image emptyImage;
    private String emptyText;

    public EmptyView() {
        emptyText = "No Android Devices!";
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (emptyImage == null) {
            try {
                emptyImage = ImageIO.read(getClass().getResource("/images/logo.png"));
            } catch (IOException e) {
                log.debug("paintComponent: {}", e.getMessage());
            }
        }

        int w = getWidth();
        int h = getHeight();
        int imageW = emptyImage.getWidth(null);
        int imageH = emptyImage.getHeight(null);
        if (imageW > w || imageH > h) return;

        int x = w / 2 - (imageW / 2);
        int y = h / 2 - (imageH / 2);
        g.drawImage(emptyImage, x, y, null);

        y += imageH;
        int textW = g.getFontMetrics().stringWidth(emptyText);
        x = w / 2 - (textW / 2);
        g.drawString(emptyText, x, y);
    }

    public void setEmptyText(String emptyText) {
        this.emptyText = emptyText;
    }

    public void setEmpty(boolean empty) {
        setVisible(empty);
    }

}
