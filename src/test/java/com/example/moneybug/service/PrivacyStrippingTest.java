package com.example.moneybug.service;

import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.StoreWorker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyStrippingTest {

    private static StaffDto staff() {
        UserDto.Response p = UserDto.Response.builder().id(7).kakaoId("k").name("민수").email("a@b.c")
                .contact("010").certHasPhoto(true).build();
        return new StaffDto(1, 1, 7, p, "오픈", null, 12000, StoreWorker.TaxType.NONE, true, true, null,
                StoreWorker.Status.ACTIVE, null, null, null, List.of(), List.of());
    }

    @Test
    void coworkerStripped() {
        StaffDto d = staff().forViewer(false, 8);
        assertNull(d.person().getEmail());
        assertNull(d.person().getContact());
        assertNull(d.person().getKakaoId());
        assertEquals("민수", d.person().getName());
        assertTrue(d.person().isCertHasPhoto());
        assertEquals(0, d.hourlyWage());
        assertNull(d.taxType());
    }

    @Test
    void ownerAndSelfKeepFullRow() {
        StaffDto s = staff();
        assertSame(s, s.forViewer(true, 99));
        assertSame(s, s.forViewer(false, 7));
    }

    @Test
    void storeCodeAndTotalsHiddenFromNonOwner() {
        var r = new StoreDetailDto.Response(1, 1, "n", "a", "ABCD-EFGH", false, 0, 0, 0, 0, null, false, false,
                null, null, List.of(), Map.of("2026-10", 5));
        var out = r.withoutOwnerData();
        assertEquals("", out.code());
        assertTrue(out.monthlyTotals().isEmpty());
    }

    @Test
    void draftMonthShiftsHidden() {
        Schedule draft = new Schedule();
        draft.setWorkDate(LocalDate.of(2026, 11, 3));
        Schedule noMeta = new Schedule();
        noMeta.setWorkDate(LocalDate.of(2026, 10, 3));
        assertEquals(List.of(noMeta), StateService.visibleShifts(List.of(draft, noMeta), Set.of("2026-11")));
    }
}
