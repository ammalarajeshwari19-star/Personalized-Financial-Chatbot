package com.finn.dto;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AAConsentResponse {
    private String  consentHandle;
    private String  redirectUrl;
    private String  status;
    private boolean mock;
}
