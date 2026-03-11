package com.finn.dto;
import lombok.*;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class LinkedAccountResponse {
    private Long          id;
    private String        maskedAccount;
    private String        bankName;
    private String        branchName;
    private String        ifscCode;
    private String        holderName;
    private String        upiId;
    private String        cardMasked;
    private String        cardNetwork;
    private String        consentStatus;
    private LocalDateTime lastSyncedAt;
    private boolean       primary;
}
