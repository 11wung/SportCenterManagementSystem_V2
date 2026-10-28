package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Strict request parsing scoped to Flow 4; other flows keep their JSON contract. */
public final class TrainingNumbers {
    private TrainingNumbers() {}

    public static final class IntegerValue extends StdDeserializer<Integer> {
        public IntegerValue() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return parser.getIntValue();
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
    }

    public static final class LongValue extends StdDeserializer<Long> {
        public LongValue() { super(Long.class); }
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return parser.getLongValue();
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
    }
}
