package com.example.moneybug.entity;

/**
 * FE(Flutter) 계약: enum JSON 값은 Dart enum 의 .name (소문자 camel, 예: PART_TIMER -> "partTimer").
 * 받을 때는 대소문자·밑줄을 무시해 예전 값("PART_TIMER")도 받는다.
 */
public final class EnumJson {

    private EnumJson() {}

    public static String camel(Enum<?> e) {
        String[] parts = e.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) return null;
        String v = value.trim().replace("_", "");
        for (E e : type.getEnumConstants()) {
            if (e.name().replace("_", "").equalsIgnoreCase(v)) return e;
        }
        throw new IllegalArgumentException("알 수 없는 값이에요: '" + value + "'");
    }
}
