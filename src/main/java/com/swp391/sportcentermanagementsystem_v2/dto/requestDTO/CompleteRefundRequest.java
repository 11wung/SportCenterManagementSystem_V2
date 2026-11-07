package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompleteRefundRequest {

    // Mã 6 số hội viên đọc cho quầy
    @NotBlank(message = "Hãy nhập mã nhận tiền của hội viên")
    @Pattern(regexp = "\\d{6}", message = "Mã nhận tiền gồm 6 chữ số")
    private String code;
}
