package com.wherelee.cabinet.common.mask;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

/**
 * {@link JsonMask} 的序列化实现。
 *
 * <p>实现 {@link ContextualSerializer} 是必须的：Jackson 只为 String 类型注册一个共享 serializer，
 * 遮蔽样式（PHONE/ID_CARD/...）只能从"当前这个字段"的注解上读出来，
 * 所以每个字段要返回一个带自己 type 的实例。少了这一步，所有字段都会用同一个样式（或抛 NPE）。
 */
public class JsonMaskSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private final MaskType maskType;

    public JsonMaskSerializer() {
        this(MaskType.ALL);
    }

    private JsonMaskSerializer(MaskType maskType) {
        this.maskType = maskType;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        // null 不会走到这里（Jackson 对 null 走 null serializer）；空串按原样写出
        gen.writeString(maskType.mask(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property)
            throws JsonMappingException {
        if (property == null) {
            return this;
        }
        JsonMask mask = property.getAnnotation(JsonMask.class);
        if (mask == null && property.getMember() != null) {
            // record / 字段注解不在 getter 上时，从承载成员（accessor）上读
            mask = property.getMember().getAnnotation(JsonMask.class);
        }
        return mask == null ? this : new JsonMaskSerializer(mask.value());
    }
}
