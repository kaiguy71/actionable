package dev.actionable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses Minecraft's log configuration, including logs/latest.log and archived session logs. */
final class ActionableLog {
    static final Logger LOGGER = LoggerFactory.getLogger("actionable");

    private ActionableLog() { }

    /** Full provider reply, JSON-escaped onto one log line without truncating or parsing it. */
    static String modelResponseBody(long id, String stage, String provider, String body) {
        return "[Actionable] model_response_body id=" + id + " stage=" + text(stage)
                + " provider=" + text(provider) + " body=" + new com.google.gson.JsonPrimitive(body);
    }

    static String text(String value) {
        if (value == null) {
            return "none";
        }
        return value.codePoints().filter(point -> !Character.isISOControl(point)).limit(500)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
