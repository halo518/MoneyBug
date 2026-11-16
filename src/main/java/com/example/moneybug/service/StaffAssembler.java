package com.example.moneybug.service;

import com.example.moneybug.dto.ContractDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** StoreWorker -> StaffDto (person 조인 + 가장 최근 계약서). */
@Component
@RequiredArgsConstructor
public class StaffAssembler {

    private final ContractRepository contractRepository;

    public StaffDto toDto(StoreWorker sw) {
        ContractDto.Response contract = contractRepository
                .findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId())
                .map(ContractDto.Response::from)
                .orElse(null);
        return StaffDto.from(sw, contract);
    }
}
