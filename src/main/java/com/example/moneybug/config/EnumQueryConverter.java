package com.example.moneybug.config;

import com.example.moneybug.entity.EnumJson;
import com.example.moneybug.entity.StoreWorker;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** `?status=pending` 처럼 FE 소문자 값도 받는다. 빈 값은 null(= 필터 없음). */
@Component
public class EnumQueryConverter implements Converter<String, StoreWorker.Status> {

    @Override
    public StoreWorker.Status convert(String source) {
        return source.isBlank() ? null : EnumJson.parse(StoreWorker.Status.class, source);
    }
}
