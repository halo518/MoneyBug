package com.example.moneybug.service;

import com.example.moneybug.dto.MeDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** 내 정보 (설계서 §4.1). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MeService {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final StaffAssembler staffAssembler;

    /**
     * GET /me — person + 소유 매장 + 소속 매장(ACTIVE/PENDING).
     * 가입 승인 대기 화면은 이 응답의 memberships[].status 를 폴링해 판단한다(§10-11).
     */
    public MeDto.Response getMe(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        List<Integer> ownedIds = storeRepository.findByEmployerId(userId).stream()
                .map(Store::getId).sorted().toList();
        List<StaffDto> memberships = storeWorkerRepository
                .findByWorkerIdAndStatusIn(userId, List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING))
                .stream()
                .sorted(Comparator.comparing(StoreWorker::getId))
                .map(staffAssembler::toDto)
                .toList();
        return new MeDto.Response(UserDto.Response.from(user),
                ownedIds.isEmpty() ? null : ownedIds.get(0), ownedIds, memberships);
    }
}
