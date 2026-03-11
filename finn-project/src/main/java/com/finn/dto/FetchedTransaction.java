package com.finn.dto;
import lombok.*;
import java.time.LocalDate;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class FetchedTransaction {
    private String    refId;
    private double    amount;
    private String    type;
    private String    description;
    private LocalDate date;
    private String    merchantName;
}
