package com.codewitheli.vaultlocal.adapter.ui.controls;

import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

import java.util.Locale;

/**
 * Coloured rounded square with a letter or a simple glyph (decision Q7: no brand logos are
 * bundled). Everything is drawn locally; no images are loaded.
 */
public final class PlatformBadge extends StackPane {

    private static final double SIZE = 24;

    /** Bank: columns under a roof. Other: three dots. Both drawn from these paths. */
    private static final String BANK_GLYPH = "M12 2 L22 7 L22 9 L2 9 L2 7 Z M4 10 H7 V17 H4 Z M10.5 10 H13.5 V17 H10.5 Z "
            + "M17 10 H20 V17 H17 Z M2 18 H22 V21 H2 Z";
    private static final String OTHER_GLYPH = "M5 10 A2 2 0 1 0 5 14 A2 2 0 1 0 5 10 Z M12 10 A2 2 0 1 0 12 14 A2 2 0 1 0 12 10 Z "
            + "M19 10 A2 2 0 1 0 19 14 A2 2 0 1 0 19 10 Z";

    public PlatformBadge(Platform platform) {
        getStyleClass().add("platform-badge");
        Rectangle bg = new Rectangle(SIZE, SIZE);
        bg.setArcWidth(8);
        bg.setArcHeight(8);
        bg.setFill(color(platform));
        getChildren().add(bg);
        PlatformKind kind = platform.kind();
        if (kind == PlatformKind.BANK || kind == PlatformKind.OTHER) {
            SVGPath glyph = new SVGPath();
            glyph.setContent(kind == PlatformKind.BANK ? BANK_GLYPH : OTHER_GLYPH);
            glyph.setFill(Color.WHITE);
            glyph.setScaleX(0.62);
            glyph.setScaleY(0.62);
            getChildren().add(glyph);
        } else {
            Label letter = new Label(letter(platform));
            letter.getStyleClass().add("platform-badge-letter");
            letter.setTextFill(Color.WHITE);
            getChildren().add(letter);
        }
        setMinSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
    }

    static String letter(Platform p) {
        return switch (p.kind()) {
            case FACEBOOK -> "f";
            case TIKTOK -> "T";
            case GMAIL -> "G";
            case INSTAGRAM -> "Ig";
            case X -> "X";
            default -> p.displayName().substring(0, 1).toUpperCase(Locale.ROOT);
        };
    }

    static Color color(Platform p) {
        return switch (p.kind()) {
            case FACEBOOK -> Color.web("#1877F2");
            case TIKTOK -> Color.web("#111111");
            case GMAIL -> Color.web("#D93025");
            case INSTAGRAM -> Color.web("#C13584");
            case X -> Color.web("#000000");
            case BANK -> Color.web("#2F7D4F");
            case OTHER -> Color.web("#607080");
            case CUSTOM -> Color.hsb(Math.floorMod(p.customName().toLowerCase(Locale.ROOT).hashCode(), 360), 0.55, 0.62);
        };
    }
}
