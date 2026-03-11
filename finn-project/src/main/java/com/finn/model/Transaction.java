package com.finn.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "txn_ref_id"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Transaction {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id")
    private LinkedAccount linkedAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type = TransactionType.DEBIT;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Column(name = "txn_ref_id")
    private String txnRefId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.MANUAL;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "upi_ref")
    private String upiRef;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum TransactionType { DEBIT, CREDIT }
    public enum Source           { MANUAL, AA_FETCH, UPI }
}
