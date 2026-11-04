package com.codewitheli.vaultlocal.adapter.ui.controls;

import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.Arrays;

/**
 * Masked input that keeps its content in a {@code char[]} (DESIGN.md §6.3). Unlike
 * {@code PasswordField}, the whole secret never exists as one {@link String} while masked.
 * Limits: typed characters arrive from JavaFX as 1-char strings, paste goes through the OS
 * clipboard string, and the show toggle renders plaintext through a {@link Text} node.
 *
 * <p>Editing: typing, Backspace, Delete, ←/→/Home/End, Ctrl+A, Ctrl+V. Copy/cut are disabled.
 */
public final class SecretField extends Region {

    private static final String BULLET = "•";

    private char[] buffer = new char[32];
    private int length;
    private int caret;
    private boolean allSelected;
    private double scrollX;

    private final Text text = new Text();
    private final Text prompt = new Text();
    private final Text measure = new Text();
    private final Line caretLine = new Line();
    private final Rectangle selection = new Rectangle();
    private final Rectangle clip = new Rectangle();
    private final Timeline blink;

    private final BooleanProperty revealed = new SimpleBooleanProperty(this, "revealed", false);
    private final StringProperty promptText = new SimpleStringProperty(this, "promptText", "");
    private final IntegerProperty charCount = new SimpleIntegerProperty(this, "charCount", 0);
    private final IntegerProperty revision = new SimpleIntegerProperty(this, "revision", 0);
    private EventHandler<ActionEvent> onAction;

    public SecretField() {
        getStyleClass().add("secret-field");
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.PASSWORD_FIELD);
        accessibleTextProperty().bind(promptText);

        text.getStyleClass().add("secret-text");
        prompt.getStyleClass().add("secret-prompt");
        text.setTextOrigin(VPos.TOP);
        prompt.setTextOrigin(VPos.TOP);
        prompt.textProperty().bind(promptText);
        measure.fontProperty().bind(text.fontProperty());
        caretLine.getStyleClass().add("secret-caret");
        selection.getStyleClass().add("secret-selection");
        selection.setVisible(false);
        caretLine.setVisible(false);
        setClip(clip);
        getChildren().addAll(selection, prompt, text, caretLine);

        blink = new Timeline(new KeyFrame(Duration.millis(530), e -> caretLine.setVisible(!caretLine.isVisible())));
        blink.setCycleCount(Timeline.INDEFINITE);

        addEventHandler(KeyEvent.KEY_TYPED, this::onKeyTyped);
        addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            requestFocus();
            allSelected = false;
            caret = length;
            render();
            // Like TextField: otherwise an enclosing ScrollPane takes the focus back on press.
            e.consume();
        });
        focusedProperty().addListener((obs, was, is) -> {
            if (is) {
                caretLine.setVisible(true);
                blink.playFromStart();
            } else {
                blink.stop();
                caretLine.setVisible(false);
                allSelected = false;
            }
            render();
        });
        revealed.addListener((obs, was, is) -> render());
        render();
    }

    // ------------------------------------------------------------------ public API

    /** Copies the content out and clears the field. The caller owns (and closes) the result. */
    public Secret takeSecret() {
        Secret s = Secret.copyOf(buffer, 0, length);
        clear();
        return s;
    }

    /** Copies the content out without clearing. The caller owns (and closes) the result. */
    public Secret copySecret() {
        return Secret.copyOf(buffer, 0, length);
    }

    /** Replaces the content with a copy of {@code secret}; the caller keeps ownership. */
    public void setSecret(Secret secret) {
        clear();
        insert(secret.chars(), 0, secret.length());
    }

    /** True when both fields hold exactly the same characters. No copies are made. */
    public boolean contentEquals(SecretField other) {
        int diff = length ^ other.length;
        int n = Math.min(length, other.length);
        for (int i = 0; i < n; i++) {
            diff |= buffer[i] ^ other.buffer[i];
        }
        return diff == 0;
    }

    public int codePointCount() {
        return Character.codePointCount(buffer, 0, length);
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public void clear() {
        Arrays.fill(buffer, '\0');
        length = 0;
        caret = 0;
        allSelected = false;
        changed();
    }

    public BooleanProperty revealedProperty() {
        return revealed;
    }

    public void setRevealed(boolean value) {
        revealed.set(value);
    }

    public StringProperty promptTextProperty() {
        return promptText;
    }

    public void setPromptText(String value) {
        promptText.set(value);
    }

    /** UTF-16 length, for enabling buttons. Never exposes content. */
    public ReadOnlyIntegerProperty charCountProperty() {
        return charCount;
    }

    /** Increments on every edit, so listeners can react (e.g. a strength meter). */
    public ReadOnlyIntegerProperty revisionProperty() {
        return revision;
    }

    public void setOnAction(EventHandler<ActionEvent> handler) {
        this.onAction = handler;
    }

    // ------------------------------------------------------------------ editing

    private void onKeyTyped(KeyEvent e) {
        String typed = e.getCharacter();
        if (typed == null || typed.isEmpty() || typed.equals(KeyEvent.CHAR_UNDEFINED)
                || e.isMetaDown() || (e.isControlDown() && !e.isAltDown())) {
            return;
        }
        char[] chars = typed.toCharArray();
        int n = 0;
        for (char c : chars) {
            if (!Character.isISOControl(c)) {
                chars[n++] = c;
            }
        }
        if (n > 0) {
            insert(chars, 0, n);
        }
        Arrays.fill(chars, '\0');
        e.consume();
    }

    private void onKeyPressed(KeyEvent e) {
        boolean shortcut = e.isShortcutDown();
        KeyCode code = e.getCode();
        switch (code) {
            case BACK_SPACE -> {
                if (allSelected) {
                    clear();
                } else if (caret > 0) {
                    int start = Character.offsetByCodePoints(buffer, 0, length, caret, -1);
                    remove(start, caret);
                }
                e.consume();
            }
            case DELETE -> {
                if (allSelected) {
                    clear();
                } else if (caret < length) {
                    int end = Character.offsetByCodePoints(buffer, 0, length, caret, 1);
                    remove(caret, end);
                }
                e.consume();
            }
            case LEFT -> {
                allSelected = false;
                if (caret > 0) {
                    caret = Character.offsetByCodePoints(buffer, 0, length, caret, -1);
                }
                render();
                e.consume();
            }
            case RIGHT -> {
                allSelected = false;
                if (caret < length) {
                    caret = Character.offsetByCodePoints(buffer, 0, length, caret, 1);
                }
                render();
                e.consume();
            }
            case HOME -> {
                allSelected = false;
                caret = 0;
                render();
                e.consume();
            }
            case END -> {
                allSelected = false;
                caret = length;
                render();
                e.consume();
            }
            case ENTER -> {
                if (onAction != null) {
                    onAction.handle(new ActionEvent(this, this));
                }
                e.consume();
            }
            case A -> {
                if (shortcut) {
                    allSelected = length > 0;
                    render();
                    e.consume();
                }
            }
            case V -> {
                if (shortcut) {
                    paste();
                    e.consume();
                }
            }
            case C, X -> {
                if (shortcut) {
                    e.consume();
                }
            }
            default -> {
            }
        }
    }

    private void paste() {
        String content = Clipboard.getSystemClipboard().getString();
        if (content == null) {
            return;
        }
        char[] chars = content.toCharArray();
        int n = 0;
        for (char c : chars) {
            if (!Character.isISOControl(c)) {
                chars[n++] = c;
            }
        }
        insert(chars, 0, n);
        Arrays.fill(chars, '\0');
    }

    private void insert(char[] src, int offset, int n) {
        if (allSelected) {
            Arrays.fill(buffer, '\0');
            length = 0;
            caret = 0;
            allSelected = false;
        }
        if (length + n > buffer.length) {
            char[] bigger = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + n));
            Arrays.fill(buffer, '\0');
            buffer = bigger;
        }
        System.arraycopy(buffer, caret, buffer, caret + n, length - caret);
        System.arraycopy(src, offset, buffer, caret, n);
        length += n;
        caret += n;
        changed();
    }

    private void remove(int start, int end) {
        System.arraycopy(buffer, end, buffer, start, length - end);
        int removed = end - start;
        Arrays.fill(buffer, length - removed, length, '\0');
        length -= removed;
        caret = start;
        changed();
    }

    private void changed() {
        charCount.set(length);
        revision.set(revision.get() + 1);
        render();
    }

    // ------------------------------------------------------------------ rendering

    private String display(int upTo) {
        if (revealed.get()) {
            return new String(buffer, 0, upTo);
        }
        return BULLET.repeat(Character.codePointCount(buffer, 0, upTo));
    }

    private void render() {
        text.setText(display(length));
        prompt.setVisible(length == 0 && !isFocused());
        if (isFocused()) {
            caretLine.setVisible(true);
            blink.playFromStart();
        }
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double w = getWidth();
        double h = getHeight();
        double contentW = Math.max(0, w - in.getLeft() - in.getRight());
        double textH = text.getLayoutBounds().getHeight();
        double y = in.getTop() + Math.max(0, (h - in.getTop() - in.getBottom() - textH) / 2);

        measure.setText(display(caret));
        double caretX = measure.getLayoutBounds().getWidth();
        measure.setText("");
        if (caretX - scrollX > contentW - 2) {
            scrollX = caretX - contentW + 2;
        } else if (caretX < scrollX) {
            scrollX = caretX;
        }
        if (length == 0) {
            scrollX = 0;
        }

        text.relocate(in.getLeft() - scrollX, y);
        prompt.relocate(in.getLeft(), y);
        double cx = Math.round(in.getLeft() + caretX - scrollX) + 0.5;
        caretLine.setStartX(cx);
        caretLine.setEndX(cx);
        caretLine.setStartY(y + 1);
        caretLine.setEndY(y + Math.max(textH, 14) - 1);

        selection.setVisible(allSelected && length > 0);
        selection.setX(in.getLeft() - scrollX);
        selection.setY(y);
        selection.setWidth(text.getLayoutBounds().getWidth());
        selection.setHeight(Math.max(textH, 14));

        clip.setWidth(w);
        clip.setHeight(h);
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets in = getInsets();
        measure.setText("Ag");
        double h = measure.getLayoutBounds().getHeight();
        measure.setText("");
        return in.getTop() + h + in.getBottom();
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets in = getInsets();
        return in.getLeft() + 220 + in.getRight();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    public String toString() {
        return "SecretField[***]";
    }
}
