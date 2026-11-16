package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** [신규] 매장 파트(오픈·미들·마감 등) (설계서 §3.2 parts[], §8 store_parts). */
@Entity
@Table(name = "store_part", uniqueConstraints = {
        @UniqueConstraint(name = "uk_store_part_name", columnNames = {"store_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
public class StorePart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "part_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 50)
    private String name;

    // 하루 기준 분 (Schedule 과 동일 규칙)
    @Column(name = "start_min", nullable = false)
    private Integer startMin;

    @Column(name = "end_min", nullable = false)
    private Integer endMin;

    // 그 파트의 하루 필요 인원
    @Column(nullable = false)
    private Integer need = 1;
}
