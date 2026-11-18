package com.example.moneybug.service;

import com.example.moneybug.dto.ContractDto;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 계약서 작성·서명 규칙 (QA 17). */
class DocumentContractTest {

    private static final int OWNER = 1, EMP = 7, STORE = 3, STAFF = 66;
    private static final String SIG = "signatures/7/a.png";

    private ContractRepository contracts;
    private StoreAccess access;
    private FileStorage files;
    private StaffWageService wages;
    private StoreRepository stores;
    private NotificationPublisher notifier;
    private DocumentService svc;
    private StoreWorker sw;
    private Store store;

    @BeforeEach
    void setUp() {
        contracts = mock(ContractRepository.class);
        access = mock(StoreAccess.class);
        files = mock(FileStorage.class);
        wages = mock(StaffWageService.class);
        stores = mock(StoreRepository.class);
        notifier = mock(NotificationPublisher.class);
        svc = new DocumentService(mock(UserRepository.class), mock(StoreWorkerRepository.class), contracts, access,
                notifier, files, wages, stores);
        User emp = new User();
        emp.setId(EMP);
        emp.setName("알바");
        User owner = new User();
        owner.setId(OWNER);
        store = new Store();
        store.setId(STORE);
        store.setEmployer(owner);
        sw = new StoreWorker();
        sw.setId(STAFF);
        sw.setWorker(emp);
        sw.setStore(store);
        sw.setStatus(StoreWorker.Status.ACTIVE);
        when(access.store(STORE)).thenReturn(store);
        when(access.staff(STORE, STAFF)).thenReturn(sw);
        when(access.requireOwner(OWNER, STORE)).thenReturn(store);
    }

    private Contract contract(int id, boolean signed) {
        Contract c = new Contract();
        c.setId(id);
        c.setStoreWorker(sw);
        c.setStartDate(LocalDate.of(2026, 11, 20));
        c.setEndDate(LocalDate.of(2027, 11, 19));
        c.setWage(10320);
        c.setWeeklyHours(20);
        c.setWorkDays("월");
        c.setDuty("");
        c.setOwnerSignedAt(LocalDateTime.now());
        if (signed) c.setEmployeeSignedAt(LocalDateTime.now());
        return c;
    }

    private static ContractDto.SignRequest sign(Integer contractId) {
        ContractDto.SignRequest r = new ContractDto.SignRequest();
        r.setSignatureUrl(SIG);
        r.setContractId(contractId);
        return r;
    }

    private static ContractDto.Request write(int wage, String duty) {
        ContractDto.Request r = new ContractDto.Request();
        r.setStartDate(LocalDate.of(2026, 11, 20));
        r.setEndDate(LocalDate.of(2027, 11, 19));
        r.setWage(wage);
        r.setWeeklyHours(20);
        r.setWorkDays("월·수·금");
        r.setDuty(duty);
        return r;
    }

    @Test
    void signRejectsStaleContractId() {
        when(contracts.findFirstForUpdateByStoreWorkerIdOrderByCreatedAtDescIdDesc(STAFF))
                .thenReturn(Optional.of(contract(17, false)));
        ApiException e = assertThrows(ApiException.class, () -> svc.signContract(EMP, STORE, STAFF, sign(6), "ip"));
        assertEquals("CONTRACT_CHANGED", e.getCode());
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        verify(notifier, never()).sendToOwner(any(), any(), anyString(), any(), any());
    }

    @Test
    void signWithMatchingOrMissingContractIdUsesLockedRow() {
        Contract c = contract(17, false);
        when(contracts.findFirstForUpdateByStoreWorkerIdOrderByCreatedAtDescIdDesc(STAFF)).thenReturn(Optional.of(c));
        assertTrue(svc.signContract(EMP, STORE, STAFF, sign(17), "ip").isSigned());
        verify(contracts, never()).findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(anyInt());
        assertEquals(SIG, c.getEmployeeSignatureUrl());
        ApiException e = assertThrows(ApiException.class, () -> svc.signContract(EMP, STORE, STAFF, sign(null), "ip"));
        assertEquals("ALREADY_SIGNED", e.getCode());
    }

    @Test
    void signRejectsMissingSignatureFile() {
        when(files.read(SIG)).thenThrow(ApiException.notFound("파일을 찾을 수 없어요"));
        ApiException e = assertThrows(ApiException.class, () -> svc.signContract(EMP, STORE, STAFF, sign(null), "ip"));
        assertEquals("INVALID_SIGNATURE", e.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void signaturePathRejectsUnknownWho() {
        for (String who : new String[]{"foo", "OWNER", ""}) {
            ApiException e = assertThrows(ApiException.class, () -> svc.signaturePath(OWNER, STORE, STAFF, who));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
    }

    @Test
    void writeChecksMinimumWageInServiceAndCountsDutyByCharacters() {
        ApiException e = assertThrows(ApiException.class,
                () -> svc.upsertContract(OWNER, STORE, STAFF, write(10319, "")));
        assertEquals("WAGE_BELOW_MINIMUM", e.getCode());
        verify(stores).findByIdForUpdate(STORE); // 매장 잠금이 먼저

        when(contracts.save(any())).thenAnswer(i -> { Contract c = i.getArgument(0); c.setId(20); return c; });
        when(contracts.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(STAFF))
                .thenReturn(Optional.of(contract(19, false)));
        svc.upsertContract(OWNER, STORE, STAFF, write(10320, "😀".repeat(100)));
        verify(wages).recordContract(eq(sw), eq(20), eq(LocalDate.of(2026, 11, 20)), eq(LocalDate.of(2027, 11, 19)),
                eq(10320), eq(19));

        ApiException tooLong = assertThrows(ApiException.class,
                () -> svc.upsertContract(OWNER, STORE, STAFF, write(10320, "😀".repeat(101))));
        assertEquals("DUTY_TOO_LONG", tooLong.getCode());
    }

    @Test
    void dtoCapsWageAndWeeklyHoursButLetsServiceJudgeMinimumWage() {
        var v = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertTrue(v.validate(write(10000, "😀".repeat(100))).isEmpty()); // 최저임금·글자 수는 서비스가 판단
        assertEquals(1, v.validate(write(ContractDto.MAX_WAGE + 1, "")).size());
        ContractDto.Request r = write(10320, "");
        r.setWeeklyHours(53);
        assertEquals(1, v.validate(r).size());
    }

    @Test
    void lockedFinderNameIsAValidDerivedQuery() {
        var tree = new org.springframework.data.repository.query.parser.PartTree(
                "findFirstForUpdateByStoreWorkerIdOrderByCreatedAtDescIdDesc", Contract.class);
        assertEquals(1, tree.getMaxResults());
    }
}
