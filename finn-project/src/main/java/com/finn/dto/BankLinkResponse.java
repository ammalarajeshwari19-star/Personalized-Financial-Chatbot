package com.finn.dto;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class BankLinkResponse {
    private Long   accountId;
    private String maskedAccount;
    private String bankName;
    private String aaConsentHandle;
    private String aaRedirectUrl;
    private String consentStatus;
    private String message;
}
