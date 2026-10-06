package dev.actionable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses Minecraft's log configuration, including logs/latest.log and archived session logs. */
final class ActionableLog {
    static final Logger LOGGER = LoggerFactory.getLogger("actionable");

    private ActionableLog() { }

    static String text(String value) {
        if (value == null) {
            return "none";
        }
        return value.codePoints().filter(point -> !Character.isISOControl(point)).limit(500)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
