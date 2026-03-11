package com.finn.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "linked_accounts")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class LinkedAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Account
    @Column(name = "account_number_hash", nullable = false)
    private String accountNumberHash;

    @Column(name = "account_last_four", nullable = false, length = 4)
    private String accountLastFour;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType = AccountType.SAVINGS;

    @Column(name = "ifsc_code", nullable = false, length = 11)
    private String ifscCode;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    // UPI
    @Column(name = "upi_id")
    private String upiId;

    // Debit card — NEVER full number or CVV
    @Column(name = "card_last_four", length = 4)
    private String cardLastFour;

    @Column(name = "card_expiry_month")
    private Integer cardExpiryMonth;

    @Column(name = "card_expiry_year")
    private Integer cardExpiryYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_network")
    private CardNetwork cardNetwork = CardNetwork.RUPAY;

    // AA fields
    @Column(name = "aa_consent_id")
    private String aaConsentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "aa_consent_status")
    private ConsentStatus aaConsentStatus = ConsentStatus.PENDING;

    @Column(name = "aa_fip_id")
    private String aaFipId;

    @Column(name = "aa_linked_ref_id")
    private String aaLinkedRefId;

    @Column(name = "is_primary")
    private boolean primary = false;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum AccountType    { SAVINGS, CURRENT, OD }
    public enum CardNetwork    { VISA, MASTERCARD, RUPAY }
    public enum ConsentStatus  { PENDING, ACTIVE, REVOKED, EXPIRED }
}
