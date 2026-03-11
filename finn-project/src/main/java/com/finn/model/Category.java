package com.finn.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "categories")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Category {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "icon_emoji")
    private String iconEmoji;

    @Column(name = "color_hex", length = 7)
    private String colorHex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "is_default")
    private boolean defaultCategory = false;
}
