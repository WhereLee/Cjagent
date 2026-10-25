package com.wherelee.cabinet.common.mask;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

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
            mask = property.getMember().getAnnotation(JsonMask.class);
        }
        return mask == null ? this : new JsonMaskSerializer(mask.value());
    }
}
