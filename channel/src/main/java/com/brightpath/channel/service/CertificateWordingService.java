package com.brightpath.channel.service;

import freemarker.template.Configuration;
import freemarker.template.Template;
import jakarta.inject.Singleton;

import java.io.StringWriter;
import java.util.Map;

/**
 * Renders the school's editable certificate wording. Course admins supply the
 * wording template and preview it with sample completion data before it is
 * applied to a cohort.
 */
@Singleton
public class CertificateWordingService {

    private final Configuration cfg = new Configuration(Configuration.VERSION_2_3_34);

    public String render(String source, Map<String, Object> model) {
        try {
            Template wording = new Template("wording", source, cfg);
            StringWriter out = new StringWriter();
            wording.process(model, out);
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("certificate wording could not be rendered", e);
        }
    }
}
