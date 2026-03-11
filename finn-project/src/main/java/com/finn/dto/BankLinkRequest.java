package com.finn.dto;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class BankLinkRequest {
    private Long    userId;
    private String  accountNumber;
    private String  accountType;
    private String  ifscCode;
    private String  bankName;
    private String  branchName;
    private String  holderName;
    private String  upiId;
    private String  cardNumber;
    private Integer cardExpiryMonth;
    private Integer cardExpiryYear;
    private String  mobileForAA;
}
