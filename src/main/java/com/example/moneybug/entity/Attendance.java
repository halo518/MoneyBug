package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "attendance")
@Getter
@Setter
@NoArgsConstructor
public class Attendance {

    // [변경] MANUAL 추가: 사장님이 출근 기록을 직접 만든 경우(createAttendance)
    public enum CheckMethod { QR, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attendance_id")
    private Integer id;

    // [변경] ManyToOne -> OneToOne + unique: 근무당 출근 기록 1건 (설계서 §8)
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false, unique = true)
    private Schedule schedule;

    // clockIn
    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    // clockOut
    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    @Column(name = "overtime_minutes")
    private Integer overtimeMinutes = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_method", nullable = false)
    private CheckMethod checkMethod;

    @Column(name = "holiday_pay_rate")
    private Integer holidayPayRate = 0;

    // [추가] 근태 수정 사유 (사장님이 수정/생성할 때 필수)
    @Column(name = "edit_reason", columnDefinition = "TEXT")
    private String editReason;
}
