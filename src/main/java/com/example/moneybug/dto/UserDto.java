package com.example.moneybug.dto;

import com.example.moneybug.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

public class UserDto {

    /** "30,14,7,0" <-> [30,14,7,0] 변환 (User.certAlertDays 는 쉼표 구분 문자열로 저장). */
    public static String joinAlertDays(List<Integer> days) {
        if (days == null || days.isEmpty()) return null;
        return String.join(",", days.stream().map(String::valueOf).toList());
    }

    public static List<Integer> splitAlertDays(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotBlank
        @Size(max = 100)
        private String kakaoId;

        @NotBlank
        @Size(max = 50)
        private String name;

        @Size(max = 50)
        private String fullName;

        @Email
        @Size(max = 100)
        private String email;

        @Size(max = 500)
        private String profileImage;

        // 선택 항목 (대타 알림 연락용)
        @Size(max = 20)
        private String contact;

        private boolean phoneConsent = false;

        public User toEntity() {
            User user = new User();
            user.setKakaoId(kakaoId);
            user.setName(name);
            user.setFullName(fullName);
            user.setEmail(email);
            user.setProfileImage(profileImage);
            user.setContact(contact);
            user.setPhoneConsent(phoneConsent);
            return user;
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private String kakaoId;
        private String name;
        private String fullName;
        private String email;
        private String profileImage;
        private String contact;
        private boolean phoneConsent;
        private LocalDate certExpiry;
        private List<Integer> certAlertDays;
        private boolean certHasPhoto;
        /** computed: 만료까지 남은 일(없으면 null, 지났으면 음수) */
        private Long certDaysLeft;

        public static Response from(User user) {
            return Response.builder()
                    .id(user.getId())
                    .kakaoId(user.getKakaoId())
                    .name(user.getName())
                    .fullName(user.getFullName())
                    .email(user.getEmail())
                    .profileImage(user.getProfileImage())
                    .contact(user.getContact())
                    .phoneConsent(user.isPhoneConsent())
                    .certExpiry(user.getCertExpiry())
                    .certAlertDays(splitAlertDays(user.getCertAlertDays()))
                    .certHasPhoto(user.getCertPhotoUrl() != null && !user.getCertPhotoUrl().isBlank())
                    .certDaysLeft(user.getCertExpiry() == null ? null
                            : ChronoUnit.DAYS.between(LocalDate.now(), user.getCertExpiry()))
                    .build();
        }
    }
}
