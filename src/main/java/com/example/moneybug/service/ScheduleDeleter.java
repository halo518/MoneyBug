package com.example.moneybug.service;

import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 근무 삭제 + 딸린 대타 요청 정리. 진행 중 대타 요청은 CANCELLED 로 바꾼 뒤,
 * FK 무결성을 위해 그 근무의 대타 요청·지원자 행을 함께 지운다.
 * (출근 기록 검사는 호출하는 쪽의 책임이다.)
 */
@Component
@RequiredArgsConstructor
public class ScheduleDeleter {

    private final ScheduleRepository scheduleRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteApplicantRepository substituteApplicantRepository;

    public void delete(Schedule schedule) {
        List<SubstituteRequest> requests = substituteRequestRepository.findByScheduleId(schedule.getId());
        for (SubstituteRequest r : requests) {
            if (r.isPending()) {
                r.setStatus(SubstituteRequest.Status.CANCELLED);
            }
            substituteApplicantRepository.deleteByRequestId(r.getId());
        }
        substituteRequestRepository.deleteAll(requests);
        scheduleRepository.delete(schedule);
    }
}
