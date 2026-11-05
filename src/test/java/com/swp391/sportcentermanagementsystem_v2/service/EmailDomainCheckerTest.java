package com.swp391.sportcentermanagementsystem_v2.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class EmailDomainCheckerTest {

    // Không gọi DNS thật trong test: chỉ thay phần tra cứu, còn lại là code thật
    private final EmailDomainChecker checker = spy(new EmailDomainChecker());

    @Test
    void aDomainThatDoesNotExistIsRejectedWithAClearMessage() {
        doReturn(false).when(checker).domainExists("gmail.con");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> checker.requireExistingDomain("an@gmail.con"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Tên miền email '@gmail.con' không tồn tại, hãy kiểm tra lại", ex.getReason());
    }

    @Test
    void aDomainThatExistsIsAccepted() {
        doReturn(true).when(checker).domainExists("gmail.com");

        assertDoesNotThrow(() -> checker.requireExistingDomain("an@gmail.com"));
    }

    @Test
    void theDomainIsReadAfterTheLastAtSignInLowerCase() {
        doReturn(true).when(checker).domainExists("gmail.com");

        checker.requireExistingDomain("An.Nguyen@GMAIL.COM");

        verify(checker).domainExists("gmail.com");
    }
}
