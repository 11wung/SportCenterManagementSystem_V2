package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;
import java.util.Locale;

// Chặn email có tên miền không tồn tại (vd. gmail.con) ngay lúc tạo tài khoản,
// để không phải gửi mã rồi nhận thư báo lỗi về hộp thư của hệ thống.
@Slf4j
@Component
public class EmailDomainChecker {

    public void requireExistingDomain(String email) {
        String domain = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        if (!domainExists(domain)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tên miền email '@" + domain + "' không tồn tại, hãy kiểm tra lại");
        }
    }

    // Tên miền nhận được thư khi có bản ghi MX, hoặc không có MX thì phải có địa chỉ (A/AAAA).
    // shortcut: lỗi mạng hay DNS chậm thì cho qua để không chặn nhầm người dùng thật,
    // hộp thư sai khi đó vẫn bị chặn ở bước nhập mã xác thực.
    boolean domainExists(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            Attributes found = new InitialDirContext(env).getAttributes(domain, new String[] {"MX", "A", "AAAA"});
            return found.size() > 0;
        } catch (NameNotFoundException ex) {
            return false;
        } catch (NamingException ex) {
            log.warn("Cannot check email domain {}, letting it through: {}", domain, ex.toString());
            return true;
        }
    }
}
