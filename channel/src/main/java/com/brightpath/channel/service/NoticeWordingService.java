package com.brightpath.channel.service;

import jakarta.inject.Singleton;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

import java.io.StringWriter;
import java.util.Map;

/**
 * Renders the school's editable notice wording (used for announcements and
 * enrollment emails). Admins supply the wording and preview it with sample
 * values before it is saved.
 */
@Singleton
public class NoticeWordingService {

    static {
        try {
            Velocity.init();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public String render(String source) {
        try {
            VelocityContext context = new VelocityContext(Map.of(
                    "org", "Brightpath Academy",
                    "student", "Sample Student",
                    "course", "Introduction to Programming"));
            StringWriter writer = new StringWriter();
            Velocity.evaluate(context, writer, "notice", source);
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("notice wording could not be rendered", e);
        }
    }
}
