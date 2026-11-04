package com.codewitheli.vaultlocal.domain.secret;

import com.codewitheli.vaultlocal.domain.error.VaultException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretTest {

    @Test
    void closeZeroesTheOwnedArray() {
        char[] raw = "hunter2-hunter2".toCharArray();
        Secret secret = Secret.wrap(raw);
        secret.close();
        assertThat(raw).containsOnly('\0');
        assertThat(secret.isClosed()).isTrue();
        assertThatThrownBy(secret::chars).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void secretBytesCloseZeroesTheOwnedArray() {
        byte[] raw = {1, 2, 3, 4};
        SecretBytes bytes = SecretBytes.wrap(raw);
        bytes.close();
        assertThat(raw).containsOnly((byte) 0);
    }

    @Test
    void toStringNeverRevealsContent() {
        try (Secret s = Secret.wrap("top-secret".toCharArray());
             SecretBytes b = SecretBytes.wrap(new byte[]{42})) {
            assertThat(s.toString()).isEqualTo("Secret[***]").doesNotContain("top");
            assertThat(b.toString()).isEqualTo("SecretBytes[***]");
        }
    }

    @Test
    void codePointCountTreatsEmojiAsOneCharacter() {
        try (Secret s = Secret.wrap("ab😀".toCharArray())) {
            assertThat(s.length()).isEqualTo(4);
            assertThat(s.codePointCount()).isEqualTo(3);
        }
    }

    @Test
    void contentEquals() {
        try (Secret a = Secret.wrap("abc".toCharArray());
             Secret b = Secret.wrap("abc".toCharArray());
             Secret c = Secret.wrap("abd".toCharArray());
             Secret d = Secret.wrap("ab".toCharArray())) {
            assertThat(a.contentEquals(b)).isTrue();
            assertThat(a.contentEquals(c)).isFalse();
            assertThat(a.contentEquals(d)).isFalse();
        }
    }

    @Test
    void utf8RoundTripWithoutStrings() {
        char[] text = "pässwörd 🔑".toCharArray();
        try (Secret original = Secret.copyOf(text, 0, text.length);
             SecretBytes utf8 = SecretCodec.utf8(original);
             Secret back = SecretCodec.fromUtf8(utf8.bytes())) {
            assertThat(back.contentEquals(original)).isTrue();
            // p ä(2) s s w ö(2) r d, space, 🔑(4) = 15 bytes
            assertThat(utf8.length()).isEqualTo(15);
        }
    }

    @Test
    void unpairedSurrogateIsRejectedWithFixedMessage() {
        try (Secret bad = Secret.wrap(new char[]{'a', '\uD800'})) {
            assertThatThrownBy(() -> SecretCodec.utf8(bad))
                    .isInstanceOf(VaultException.class)
                    .hasMessage("The text contains characters that cannot be stored.");
        }
    }
}
