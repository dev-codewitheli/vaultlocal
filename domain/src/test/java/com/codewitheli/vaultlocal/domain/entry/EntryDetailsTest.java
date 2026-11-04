package com.codewitheli.vaultlocal.domain.entry;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryDetailsTest {

    private static final Platform GMAIL = Platform.of(PlatformKind.GMAIL);

    @Test
    void requiredFieldsAreTrimmedAndOptionalBlanksBecomeNull() {
        EntryDetails d = new EntryDetails(GMAIL, "  Personal ", " me@example.com ", "  ", "");
        assertThat(d.accountLabel()).isEqualTo("Personal");
        assertThat(d.username()).isEqualTo("me@example.com");
        assertThat(d.websiteUrl()).isNull();
        assertThat(d.notes()).isNull();
    }

    @Test
    void missingRequiredFieldIsRejected() {
        assertThatThrownBy(() -> new EntryDetails(GMAIL, "Personal", " ", null, null))
                .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ENTRY_INVALID));
    }

    @Test
    void urlGetsHttpsWhenNoSchemeAndRejectsOtherSchemes() {
        assertThat(new EntryDetails(GMAIL, "a", "b", "mail.google.com", null).websiteUrl())
                .isEqualTo("https://mail.google.com");
        assertThatThrownBy(() -> new EntryDetails(GMAIL, "a", "b", "javascript://alert(1)", null))
                .isInstanceOfSatisfying(VaultException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.URL_INVALID));
        assertThatThrownBy(() -> new EntryDetails(GMAIL, "a", "b", "file:///etc/passwd", null))
                .isInstanceOf(VaultException.class);
    }

    @Test
    void platformEncodingRoundTrips() {
        for (PlatformKind kind : PlatformKind.values()) {
            Platform p = kind == PlatformKind.CUSTOM ? Platform.custom("My Credit Union") : Platform.of(kind);
            assertThat(Platform.decode(p.encode())).isEqualTo(p);
        }
        assertThat(Platform.custom("  Steam ").displayName()).isEqualTo("Steam");
    }
}
