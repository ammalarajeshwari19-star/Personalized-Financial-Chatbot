package com.finn.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bank_sync_logs")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class BankSyncLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id", nullable = false)
    private LinkedAccount linkedAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_type", nullable = false)
    private SyncType syncType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    @Column(name = "transactions_fetched")
    private Integer transactionsFetched = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum SyncType   { AA_FETCH, UPI_PULL, MANUAL }
    public enum SyncStatus { STARTED, SUCCESS, FAILED }
}
