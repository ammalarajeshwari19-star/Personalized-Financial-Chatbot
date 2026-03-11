package com.finn.dto;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SyncResponse {
    private boolean success;
    private int     transactionsFetched;
    private String  message;
}
