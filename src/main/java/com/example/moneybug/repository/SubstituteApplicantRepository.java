package com.example.moneybug.repository;

import com.example.moneybug.entity.SubstituteApplicant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubstituteApplicantRepository extends JpaRepository<SubstituteApplicant, Integer> {

    List<SubstituteApplicant> findByRequestId(Integer requestId);

    boolean existsByRequestIdAndApplicantId(Integer requestId, Integer applicantId);

    void deleteByRequestId(Integer requestId);

    void deleteByRequestIdAndApplicantId(Integer requestId, Integer applicantId);
}
