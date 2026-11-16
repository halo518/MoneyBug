package com.example.moneybug.repository;

import com.example.moneybug.entity.StorePart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StorePartRepository extends JpaRepository<StorePart, Integer> {
    List<StorePart> findByStoreIdOrderByStartMinAsc(Integer storeId);
}
