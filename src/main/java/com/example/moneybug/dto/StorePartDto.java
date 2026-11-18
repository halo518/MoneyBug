package com.example.moneybug.dto;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public class StorePartDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotNull
        private Integer storeId;

        @NotBlank
        @Size(max = 50)
        private String name;

        @NotNull
        @Min(0)
        @Max(1439)
        private Integer startMin;

        @NotNull
        @Min(0)
        @Max(1439)
        private Integer endMin;

        @NotNull
        @Min(0)
        private Integer need = 1;

        /** 연관 엔티티(매장)는 서비스에서 조회해 넘겨준다. */
        public StorePart toEntity(Store store) {
            StorePart part = new StorePart();
            part.setStore(store);
            part.setName(name);
            part.setStartMin(startMin);
            part.setEndMin(endMin);
            part.setNeed(need);
            return part;
        }
    }

    /** PUT /stores/{id}/parts. id 는 숫자 또는 문자열, 새 파트는 null·"" . */
    public record SaveRequest(@NotNull List<@NotNull(message = "비어 있는 파트가 있어요") Item> parts) {}

    public record Item(String id, String name, StorePart.Tone tone, Integer startMin, Integer endMin,
                       Integer breakMin, Integer need) {}

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer storeId;
        private String name;
        private Integer startMin;
        private Integer endMin;
        private Integer need;
        private StorePart.Tone tone;
        private Integer breakMin;

        public static Response from(StorePart part) {
            return Response.builder()
                    .id(part.getId())
                    .storeId(part.getStore().getId())
                    .name(part.getName())
                    .startMin(part.getStartMin())
                    .endMin(part.getEndMin())
                    .need(part.getNeed())
                    .tone(part.getTone())
                    .breakMin(part.getBreakMin())
                    .build();
        }
    }
}
