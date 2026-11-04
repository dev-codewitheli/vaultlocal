package com.codewitheli.vaultlocal.domain.secret;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * UTF-8 conversion between {@link Secret} and {@link SecretBytes} that never creates a
 * {@link String} and zeroes every intermediate buffer.
 */
public final class SecretCodec {

    private SecretCodec() {
    }

    public static SecretBytes utf8(Secret secret) {
        return utf8(secret.chars(), 0, secret.length());
    }

    public static SecretBytes utf8(char[] chars, int offset, int length) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer out = ByteBuffer.allocate(Math.max(1, length * 3));
        try {
            CoderResult result = encoder.encode(CharBuffer.wrap(chars, offset, length), out, true);
            if (result.isError()) {
                result.throwException();
            }
            encoder.flush(out);
            return SecretBytes.wrap(Arrays.copyOf(out.array(), out.position()));
        } catch (CharacterCodingException e) {
            throw new VaultException(ErrorCode.INVALID_TEXT);
        } finally {
            Arrays.fill(out.array(), (byte) 0);
        }
    }

    public static Secret fromUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = CharBuffer.allocate(Math.max(1, bytes.length));
        try {
            CoderResult result = decoder.decode(ByteBuffer.wrap(bytes), out, true);
            if (result.isError()) {
                result.throwException();
            }
            decoder.flush(out);
            return Secret.wrap(Arrays.copyOf(out.array(), out.position()));
        } catch (CharacterCodingException e) {
            throw new VaultException(ErrorCode.INVALID_TEXT);
        } finally {
            Arrays.fill(out.array(), '\0');
        }
    }
}
