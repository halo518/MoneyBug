package com.example.moneybug.service;

import com.example.moneybug.dto.StoreDto;
import com.example.moneybug.dto.StorePartDto;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreServicePartsTest {

    @Mock StoreRepository storeRepository;
    @Mock StorePartRepository storePartRepository;
    @Mock StoreWorkerRepository storeWorkerRepository;
    @Mock UserRepository userRepository;
    @Mock PayrollRepository payrollRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock ScheduleTemplateRepository scheduleTemplateRepository;
    @Mock StoreAccess access;
    @Mock NotificationPublisher notifier;
    @InjectMocks StoreService service;

    Store store = new Store();
    StorePart open = part(1, "오픈", 480, 720);
    StorePart close = part(2, "마감", 1020, 1320);
    StoreWorker worker = new StoreWorker();
    Schedule shift = new Schedule();

    @BeforeEach
    void setUp() {
        store.setId(10);
        com.example.moneybug.entity.User owner = new com.example.moneybug.entity.User();
        owner.setId(1);
        store.setEmployer(owner);
        open.setStore(store);
        close.setStore(store);
        when(access.requireOwner(anyInt(), anyInt())).thenReturn(store);
        when(storePartRepository.findByStoreIdOrderByStartMinAsc(10)).thenReturn(List.of(open, close));
        worker.setPart("오픈");
        StaffPrefs.setFixedParts(worker, List.of(List.of("오픈", "마감"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));
        when(storeWorkerRepository.findByStoreId(10)).thenReturn(List.of(worker));
        shift.setPart("오픈");
        when(scheduleRepository.findByStoreAndPartIn(anyInt(), anyCollection())).thenReturn(List.of(shift));
        when(scheduleTemplateRepository.findByStoreIdOrderByIdAsc(10)).thenReturn(List.of());
    }

    private static StorePart part(int id, String name, int s, int e) {
        StorePart p = new StorePart();
        p.setId(id);
        p.setName(name);
        p.setStartMin(s);
        p.setEndMin(e);
        return p;
    }

    private static StorePartDto.Item item(String id, String name, int s, int e) {
        return new StorePartDto.Item(id, name, StorePart.Tone.OPEN, s, e, 30, 1);
    }

    @Test
    void renamePropagatesToShiftsStaffAndFixedParts() {
        service.savePartList(1, 10, List.of(item("1", "오전", 480, 720), item("2", "마감", 1020, 1320)));
        assertEquals("오전", open.getName());
        assertEquals("오전", shift.getPart());
        assertEquals("오전", worker.getPart());
        assertEquals(List.of("오전", "마감"), StaffPrefs.fixedParts(worker).get(0));
    }

    @Test
    void swappedNamesAreRenamedOnce() {
        service.savePartList(1, 10, List.of(item("1", "마감", 480, 720), item("2", "오픈", 1020, 1320)));
        assertEquals("마감", open.getName());
        assertEquals("오픈", close.getName());
        assertEquals("마감", shift.getPart()); // 오픈 -> 마감, 연쇄 변경 없음
        assertEquals(List.of("마감", "오픈"), StaffPrefs.fixedParts(worker).get(0));
    }

    @Test
    void removedPartKeepsShiftNameAndNewPartIsSaved() {
        List<StorePart> saved = new ArrayList<>();
        when(storePartRepository.save(any(StorePart.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        service.savePartList(1, 10, List.of(item("1", "오픈", 480, 720), item("", "저녁", 1100, 1300)));
        org.mockito.Mockito.verify(storePartRepository).delete(close);
        assertEquals("오픈", shift.getPart());
        assertEquals(1, saved.size());
        assertEquals("저녁", saved.get(0).getName());
    }

    @Test
    void partValidation() {
        var ex = assertThrows(ApiException.class, () -> service.savePartList(1, 10, List.of(item("1", " ", 1, 2))));
        assertEquals("파트 이름을 입력하세요.", ex.getMessage());
        ex = assertThrows(ApiException.class, () -> service.savePartList(1, 10, List.of(item("1", "오픈", 600, 600))));
        assertEquals("오픈 종료 시각은 시작 시각보다 늦어야 합니다.", ex.getMessage());
        assertThrows(ApiException.class, () -> service.savePartList(1, 10,
                List.of(item("1", "같음", 480, 720), item("2", "같음", 1020, 1320))));
    }

    @Test
    void settingsValidation() {
        StoreDto.SettingsRequest r = new StoreDto.SettingsRequest();
        r.setCloseMin(400);
        var ex = assertThrows(ApiException.class, () -> service.updateSettings(1, 10, r));
        assertEquals("평일 영업 종료 시각은 시작 시각보다 늦어야 합니다.", ex.getMessage());

        store.setCloseMin(1320); // 실제로는 트랜잭션이 롤백된다
        StoreDto.SettingsRequest w = new StoreDto.SettingsRequest();
        w.setWeekendOpenMin(900);
        w.setWeekendCloseMin(900);
        ex = assertThrows(ApiException.class, () -> service.updateSettings(1, 10, w));
        assertEquals("주말·공휴일 영업 종료 시각은 시작 시각보다 늦어야 합니다.", ex.getMessage());

        store.setWeekendOpenMin(480);
        store.setWeekendCloseMin(1320);
        StoreDto.SettingsRequest h = new StoreDto.SettingsRequest();
        h.setOpenMin(0);
        h.setCloseMin(1500);
        ex = assertThrows(ApiException.class, () -> service.updateSettings(1, 10, h));
        assertEquals("영업시간은 24시간을 넘을 수 없습니다.", ex.getMessage());
    }

    @Test
    void autoSizeFollowsActiveCount() {
        when(storeWorkerRepository.countByStoreIdAndStatus(10, StoreWorker.Status.ACTIVE)).thenReturn(5L);
        StoreDto.SettingsRequest r = new StoreDto.SettingsRequest();
        r.setSizeMode(Store.SizeMode.AUTO);
        StoreDto.Response res = service.updateSettings(1, 10, r);
        assertFalse(res.isUnderFive());
        r.setSizeMode(Store.SizeMode.UNDER5);
        assertTrue(service.updateSettings(1, 10, r).isUnderFive());
    }

    @Test
    void deletedPartIsDroppedFromStaffDefaultAndFixedParts() {
        worker.setPart("마감");
        service.savePartList(1, 10, List.of(item("1", "오픈", 480, 720)));
        assertEquals("오픈", worker.getPart());
        assertEquals(List.of("오픈"), StaffPrefs.fixedParts(worker).get(0));
    }

    @Test
    void hoursOutOfRangeRejected() {
        StoreDto.SettingsRequest r = new StoreDto.SettingsRequest();
        r.setOpenMin(-600);
        r.setCloseMin(0);
        var ex = assertThrows(ApiException.class, () -> service.updateSettings(1, 10, r));
        assertEquals("INVALID_HOURS", ex.getCode());
    }
}
