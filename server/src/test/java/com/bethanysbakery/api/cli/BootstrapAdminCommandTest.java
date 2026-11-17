package com.bethanysbakery.api.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.auth.crypto.PasswordService;
import com.bethanysbakery.api.auth.crypto.QrCodeRenderer;
import com.bethanysbakery.api.auth.crypto.TotpService;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BootstrapAdminCommandTest {

    @Mock AdminUserRepository admins;
    @Mock PasswordService passwords;
    @Mock TotpService totp;
    @Mock CryptoService crypto;
    @Mock QrCodeRenderer qr;
    @Mock AuditService audit;

    private BootstrapAdminCommand command;

    @BeforeEach
    void setUp() {
        command = new BootstrapAdminCommand(admins, passwords, totp, crypto, qr, audit,
            key -> "password123456");
    }

    @Test
    void rejectsMissingArguments() {
        assertThat(command.run(Map.of())).isEqualTo(2);
        verifyNoInteractions(admins, passwords, totp, crypto, qr, audit);
    }

    @Test
    void rejectsWhenActiveAdminExists() {
        when(admins.existsByStatus(AdminUserStatus.ACTIVE)).thenReturn(true);

        assertThat(command.run(Map.of("email", "admin@example.com", "name", "Bethany"))).isEqualTo(1);
        verify(admins, never()).save(any());
    }

    @Test
    void rejectsShortPassword() {
        when(admins.existsByStatus(AdminUserStatus.ACTIVE)).thenReturn(false);
        command = new BootstrapAdminCommand(admins, passwords, totp, crypto, qr, audit,
            key -> "short");

        assertThat(command.run(Map.of("email", "admin@example.com", "name", "Bethany"))).isEqualTo(1);
        verify(admins, never()).save(any());
    }

    @Test
    void requiresPasswordWithoutTerminal() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.console() == null);
        when(admins.existsByStatus(AdminUserStatus.ACTIVE)).thenReturn(false);
        command = new BootstrapAdminCommand(admins, passwords, totp, crypto, qr, audit,
            key -> null);

        assertThat(command.run(Map.of("email", "admin@example.com", "name", "Bethany"))).isEqualTo(1);
        verify(admins, never()).save(any());
    }

    @Test
    void bootstrapsAdminWithTotpAndRecoveryCodes() {
        when(admins.existsByStatus(AdminUserStatus.ACTIVE)).thenReturn(false);
        when(passwords.hash("password123456")).thenReturn("$argon2id$hash");
        when(totp.generateSecret()).thenReturn("TOTPSECRET");
        when(crypto.encrypt("TOTPSECRET")).thenReturn("encrypted-totp");
        when(crypto.sha256Hex(anyString())).thenAnswer(invocation -> "hash-" + invocation.getArgument(0));
        when(qr.render(any())).thenReturn("QR-CODE");

        int result = command.run(Map.of("email", "Admin@Example.com", "name", "Bethany"));

        assertThat(result).isEqualTo(0);
        ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).save(saved.capture());
        AdminUser admin = saved.getValue();
        assertThat(admin.getEmail()).isEqualTo("admin@example.com");
        assertThat(admin.getName()).isEqualTo("Bethany");
        assertThat(admin.getPasswordHash()).isEqualTo("$argon2id$hash");
        assertThat(admin.getTotpSecretEncrypted()).isEqualTo("encrypted-totp");
        assertThat(admin.isTotpEnabled()).isTrue();
        assertThat(admin.getStatus()).isEqualTo(AdminUserStatus.ACTIVE);
        assertThat(admin.getRecoveryCodesHash()).hasSize(10);
        assertThat(admin.getCreatedAt()).isNotNull();
        assertThat(admin.getUpdatedAt()).isNotNull();

        verify(audit).record(eq(ActorType.SYSTEM), isNull(), eq("ADMIN_BOOTSTRAPPED"), eq("ADMIN_USER"),
            eq(admin.getId()), isNull(), any());
        verify(qr).render(any());
    }
}
