package com.example.moneybug.dto;

import com.example.moneybug.config.FeIdJsonModule;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Notice;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.ScheduleTemplate;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.User;
import com.example.moneybug.service.AttendanceCalc;
import com.example.moneybug.service.StaffPrefs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FE(Flutter) 계약: 응답 JSON 이 donBug_FE/lib/data/models.dart 의 toJson() 과 같은 키·타입·enum 값인지.
 * 만든 AppState JSON 은 build/fe-contract/state.json 에 남긴다 (FE AppState.fromJson 으로 확인용).
 */
class FeContractJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new FeIdJsonModule()).build();

    // models.dart toJson() 키
    private static final Map<String, Set<String>> FE_KEYS = Map.of(
            "persons", Set.of("id", "name", "fullName", "certExpiry", "certAlertDays", "certHasPhoto",
                    "contact", "hasPhoto", "photoVer"),
            "stores", Set.of("id", "name", "address", "code", "ownerPersonId", "isSmall", "noticeRequiredDefault",
                    "substitutePush", "defaultDeduction", "parts", "monthlyTotals", "scheduleConfirmedAt",
                    "openMin", "closeMin", "weekendOpenMin", "weekendCloseMin", "sizeMode"),
            "staff", Set.of("id", "storeId", "personId", "part", "wage", "deduction", "weeklyHolidayAuto", "premium",
                    "tone", "kind", "joinedAt", "status", "requestedAt", "contract", "fixedParts", "workHours", "availHours", "wages", "leftAt"),
            "shifts", Set.of("id", "storeId", "staffId", "date", "startMin", "endMin", "part", "breakMin",
                    "isSubstitute", "originalStaffId", "kind", "timeTbd"),
            "attendance", Set.of("id", "storeId", "shiftId", "staffId", "clockIn", "clockOut", "editReason"),
            "subs", Set.of("id", "storeId", "shiftId", "requesterId", "reason", "notifyPeers", "status",
                    "applicantIds", "acceptedId", "createdAt", "notifiedAt"),
            "notices", Set.of("id", "storeId", "title", "body", "category", "required", "pinned", "createdAt",
                    "readBy", "remindedAt"),
            "notifications", Set.of("id", "storeId", "toPersonId", "type", "title", "body", "createdAt", "read",
                    "refId"),
            "schedules", Set.of("id", "storeId", "month", "status", "source", "aiComment", "weeklyHolidayPay",
                    "priority", "updatedAt"),
            "templates", Set.of("id", "storeId", "name", "createdAt", "shifts"));

    @Test
    void appStateMatchesFeModels() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sampleState()));
        Path out = Path.of("build/fe-contract/state.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString());

        assertEquals(4, json.get("v").asInt());
        assertTrue(json.get("nextId").isInt(), "nextId 는 숫자여야 함");
        for (var e : FE_KEYS.entrySet()) {
            JsonNode item = json.get(e.getKey()).get(0);
            for (String key : e.getValue()) {
                assertTrue(item.has(key), e.getKey() + " 에 " + key + " 없음");
            }
            // FE: 모든 id·*Id 는 String
            item.properties().forEach(p -> {
                if ((p.getKey().equals("id") || p.getKey().endsWith("Id"))
                        && !p.getValue().isNull()) {
                    assertTrue(p.getValue().isString(), e.getKey() + "." + p.getKey() + " 가 문자열이 아님");
                }
            });
        }
        JsonNode person = json.get("persons").get(1);
        assertTrue(person.get("hasPhoto").isBoolean());
        assertTrue(person.get("hasPhoto").asBoolean());
        assertEquals("abc.jpg", person.get("photoVer").asString());
        assertEquals("010-1234-5678", person.get("contact").asString());
        assertTrue(json.get("persons").get(0).get("photoVer").isNull());
        assertTrue(json.get("staff").get(0).get("contract").has("start"));
        assertTrue(json.get("subs").get(0).get("applicantIds").get(0).isString());
        assertTrue(json.get("session").get("ownerStoreId").isString());

        JsonNode store = json.get("stores").get(0);
        assertEquals(480, store.get("openMin").asInt());
        assertEquals(1320, store.get("closeMin").asInt());
        assertEquals("auto", store.get("sizeMode").asString());
        JsonNode part = store.get("parts").get(0);
        assertTrue(part.get("id").isString());
        assertEquals("open", part.get("tone").asString());
        assertEquals(30, part.get("breakMin").asInt());
        JsonNode staff = json.get("staff").get(0);
        assertEquals(7, staff.get("fixedParts").size());
        assertEquals("오픈", staff.get("fixedParts").get(0).get(0).asString());
        assertEquals("2026-06-01", staff.get("leftAt").asString());
        assertEquals(2, staff.get("wages").size());
        assertEquals("2000-01-01", staff.get("wages").get(0).get("from").asString());
        assertEquals(10320, staff.get("wages").get(1).get("wage").asInt());
        assertEquals(600, staff.get("workHours").get(1).get("startMin").asInt());
        assertTrue(staff.get("workHours").get(2).isNull());
        assertEquals(7, staff.get("availHours").size());
        assertTrue(staff.get("availHours").get(0).isNull()); // 저장 안 했으면 7칸 null
        JsonNode ms = json.get("schedules").get(0);
        assertEquals("draft", ms.get("status").asString());
        assertEquals("ai", ms.get("source").asString());
        assertEquals("allow", ms.get("weeklyHolidayPay").asString());
        assertEquals("cost", ms.get("priority").asString());
        JsonNode shift = json.get("templates").get(0).get("shifts").get(0);
        assertEquals("20", shift.get("staffId").asString());
        assertEquals(0, shift.get("dayIndex").asInt());
        assertEquals("work", shift.get("kind").asString());
    }

    @Test
    void enumsUseDartNames() throws Exception {
        assertEquals("\"partTimer\"", mapper.writeValueAsString(StoreWorker.Kind.PART_TIMER));
        assertEquals("\"active\"", mapper.writeValueAsString(StoreWorker.Status.ACTIVE));
        assertEquals("\"primary\"", mapper.writeValueAsString(StoreWorker.Tone.PRIMARY));
        assertEquals("\"biz33\"", mapper.writeValueAsString(StoreWorker.TaxType.BUSINESS_TAX));
        assertEquals("\"leave\"", mapper.writeValueAsString(Schedule.Kind.LEAVE));
        assertEquals("\"under5\"", mapper.writeValueAsString(Store.SizeMode.UNDER5));
        assertEquals("\"middle\"", mapper.writeValueAsString(StorePart.Tone.MIDDLE));
        assertEquals("\"confirmed\"", mapper.writeValueAsString(MonthSchedule.Status.CONFIRMED));
        assertEquals("\"template\"", mapper.writeValueAsString(MonthSchedule.Source.TEMPLATE));
        assertEquals("\"minimize\"", mapper.writeValueAsString(MonthSchedule.HolidayPolicy.MINIMIZE));
        assertEquals("\"fairness\"", mapper.writeValueAsString(MonthSchedule.Priority.FAIRNESS));
        assertEquals("\"cancelled\"", mapper.writeValueAsString(SubstituteRequest.Status.CANCELLED));
        assertEquals("\"sub\"", mapper.writeValueAsString(Notification.Type.SUB));
        assertEquals("\"중요\"", mapper.writeValueAsString(Notice.Category.IMPORTANT));
        assertEquals("\"alreadyMember\"", mapper.writeValueAsString(StoreDetailDto.JoinResult.ALREADY_MEMBER));
        // 받을 때는 FE 값과 예전 값 둘 다
        assertEquals(StoreWorker.Kind.PART_TIMER, mapper.readValue("\"partTimer\"", StoreWorker.Kind.class));
        assertEquals(StoreWorker.Kind.PART_TIMER, mapper.readValue("\"PART_TIMER\"", StoreWorker.Kind.class));
        assertEquals(Notice.Category.LABOR, mapper.readValue("\"노무\"", Notice.Category.class));
    }

    @Test
    void requestsAcceptFeNamesAndStringIds() throws Exception {
        ShiftCommandDto.CreateRequest create = mapper.readValue(
                "{\"date\":\"2026-05-14\",\"startMin\":600,\"endMin\":960,\"part\":\"오픈\",\"staffIds\":[\"3\",\"4\"],"
                        + "\"kind\":\"work\"}", ShiftCommandDto.CreateRequest.class);
        assertEquals(List.of(3, 4), create.staffIds());
        assertEquals(Schedule.Kind.WORK, create.kind());

        StaffCommandDto.UpdateRequest staff = mapper.readValue(
                "{\"wage\":11000,\"deduction\":\"insurance\",\"kind\":\"minor\"}", StaffCommandDto.UpdateRequest.class);
        assertEquals(11000, staff.hourlyWage());
        assertEquals(StoreWorker.TaxType.FOUR_INSURANCE, staff.taxType());

        NoticeDto.Request notice = mapper.readValue(
                "{\"title\":\"t\",\"body\":\"b\",\"category\":\"중요\"}", NoticeDto.Request.class);
        assertEquals("b", notice.getContent());
        assertEquals(Notice.Category.IMPORTANT, notice.getCategory());

        ContractDto.Request contract = mapper.readValue("{\"start\":\"2026-05-01\",\"end\":\"2026-12-31\","
                + "\"wage\":10320,\"weeklyHours\":20,\"workDays\":\"월수금\",\"duty\":\"홀\"}", ContractDto.Request.class);
        assertEquals(LocalDate.of(2026, 5, 1), contract.getStartDate());
    }

    private AppStateDto.Response sampleState() {
        User owner = user(1, "사장님");
        User taewoo = user(2, "태우");
        taewoo.setPhotoUrl("profile-photo/2/abc.jpg");
        taewoo.setContact("010-1234-5678");
        Store store = new Store();
        store.setId(10);
        store.setEmployer(owner);
        store.setStoreName("카페 커푸치노");
        store.setAddress("서울");
        store.setCode("CAFE-8K2D");
        StorePart part = new StorePart();
        part.setId(1);
        part.setStore(store);
        part.setName("오픈");
        part.setStartMin(600);
        part.setEndMin(960);
        part.setNeed(1);
        part.setTone(StorePart.Tone.OPEN);
        part.setBreakMin(30);

        StoreWorker sw = new StoreWorker();
        sw.setId(20);
        sw.setStore(store);
        sw.setWorker(taewoo);
        sw.setHourlyWage(10320);
        sw.setPart("오픈");
        sw.setJoinedAt(LocalDate.of(2026, 3, 1));
        sw.setLeftAt(LocalDate.of(2026, 6, 1));
        StaffPrefs.setFixedParts(sw, List.of(List.of("오픈"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));
        StaffPrefs.setWorkHours(sw, java.util.Arrays.asList(null, new StaffPrefs.WorkHours(600, 960), null, null,
                null, null, null));
        Contract c = new Contract();
        c.setId(1);
        c.setStoreWorker(sw);
        c.setStartDate(LocalDate.of(2026, 3, 1));
        c.setEndDate(LocalDate.of(2026, 12, 31));
        c.setWage(10320);
        c.setWeeklyHours(20);
        c.setWorkDays("월수금");

        Schedule s = new Schedule();
        s.setId(30);
        s.setStoreWorker(sw);
        s.setWorkDate(LocalDate.of(2026, 5, 14));
        s.setStartMin(600);
        s.setEndMin(960);
        s.setPart("오픈");
        Attendance a = new Attendance();
        a.setId(40);
        a.setSchedule(s);
        a.setActualStart(LocalDateTime.of(2026, 5, 14, 9, 55));

        SubstituteRequest r = new SubstituteRequest();
        r.setId(50);
        r.setSchedule(s);
        r.setRequester(taewoo);
        r.setReason("병원");
        r.setCreatedAt(LocalDateTime.of(2026, 5, 13, 12, 0));

        Notice n = new Notice();
        n.setId(60);
        n.setStore(store);
        n.setTitle("공지");
        n.setContent("내용");
        n.setCreatedAt(LocalDateTime.of(2026, 5, 1, 9, 0));

        Notification noti = new Notification();
        noti.setId(70);
        noti.setStore(store);
        noti.setToUser(owner);
        noti.setType(Notification.Type.SUB);
        noti.setTitle("대타");
        noti.setBody("");
        noti.setCreatedAt(LocalDateTime.of(2026, 5, 13, 12, 0));
        noti.setRefId(50);

        MonthSchedule ms = new MonthSchedule();
        ms.setId(80);
        ms.setStore(store);
        ms.setMonth("2026-06");
        ms.setSource(MonthSchedule.Source.AI);
        ms.setAiComment("코멘트");
        ms.setWeeklyHolidayPay(MonthSchedule.HolidayPolicy.ALLOW);
        ms.setPriority(MonthSchedule.Priority.COST);

        ScheduleTemplate tpl = new ScheduleTemplate();
        tpl.setId(90);
        tpl.setStore(store);
        tpl.setName("기본 주");
        tpl.setPatternJson(ScheduleTemplateDto.toJson(List.of(new ScheduleTemplateDto.Shift(0, 20, 600, 960,
                "오픈", 30, Schedule.Kind.WORK, false))));

        return new AppStateDto.Response(AppStateDto.FE_STATE_VERSION, 1,
                new AppStateDto.Session(true, "employer", "1", "1", "", "10"),
                List.of(AppStateDto.Person.from(owner), AppStateDto.Person.from(taewoo)),
                List.of(StoreDetailDto.Response.from(store, List.of(part), Map.of())),
                List.of(StaffDto.from(sw, ContractDto.Response.from(c), List.of(wageRow(LocalDate.of(2000, 1, 1), 10000),
                        wageRow(LocalDate.of(2026, 5, 1), 10320)))),
                List.of(ScheduleDto.Response.from(s)),
                List.of(AttendanceCalc.view(a, LocalDateTime.of(2026, 5, 14, 12, 0))),
                List.of(SubstituteRequestDto.Response.from(r, List.of(taewoo), u -> u.getId() == 2 ? 20 : null)),
                List.of(NoticeViewDto.View.from(n, Map.of(20, LocalDateTime.of(2026, 5, 2, 9, 0)), true, 0)),
                List.of(NotificationDto.Response.from(noti)),
                List.of(MonthScheduleDto.Response.from(ms)),
                List.of(ScheduleTemplateDto.Response.from(tpl)));
    }

    private static com.example.moneybug.entity.StaffWage wageRow(LocalDate from, int wage) {
        var w = new com.example.moneybug.entity.StaffWage();
        w.setEffectiveFrom(from);
        w.setWage(wage);
        return w;
    }

    private static User user(int id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setFullName(name);
        u.setCertAlertDays("30,14,7,0");
        return u;
    }
}
