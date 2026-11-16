package com.example.moneybug.config;

import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.Collection;
import java.util.List;

/**
 * FE(Flutter) 계약: 모든 id 는 문자열이다 (models.dart 에서 id·*Id 가 String).
 * 응답의 숫자 속성 중 이름이 "id" 이거나 "Id"/"Ids" 로 끝나는 것을 문자열로 내보낸다.
 * 요청은 Jackson 기본 변환이 "12" 를 Integer 로 받으므로 따로 처리하지 않는다.
 */
@Component
public class FeIdJsonModule extends SimpleModule {

    public FeIdJsonModule() {
        super("FeIdJsonModule");
        setSerializerModifier(new ValueSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                             BeanDescription.Supplier beanDesc,
                                                             List<BeanPropertyWriter> props) {
                for (BeanPropertyWriter w : props) {
                    if (!isIdName(w.getName())) continue;
                    JavaType t = w.getType();
                    if (isNumber(t)) {
                        w.assignSerializer(ToStringSerializer.instance);
                    } else if (t.isCollectionLikeType() && isNumber(t.getContentType())) {
                        w.assignSerializer(STRING_LIST);
                    }
                }
                return props;
            }
        });
    }

    static boolean isIdName(String name) {
        // nextId 는 FE AppState 의 숫자 카운터라 제외
        if (name.equals("nextId")) return false;
        return name.equals("id") || name.endsWith("Id") || name.endsWith("Ids");
    }

    private static boolean isNumber(JavaType t) {
        return t != null && (Number.class.isAssignableFrom(t.getRawClass())
                || t.hasRawClass(int.class) || t.hasRawClass(long.class));
    }

    private static final ValueSerializer<Object> STRING_LIST = new ValueSerializer<>() {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartArray();
            for (Object o : (Collection<?>) value) {
                if (o == null) gen.writeNull();
                else gen.writeString(o.toString());
            }
            gen.writeEndArray();
        }
    };
}
