package com.bethanysbakery.api.config;

import com.bethanysbakery.api.notification.NotificationTemplates;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Dedicated engines: HTML escaping is Thymeleaf's default for th:text / [[...]]. */
@Configuration
public class NotificationTemplateConfig {

    @Bean("notificationHtmlEngine")
    @Primary
    public SpringTemplateEngine notificationHtmlEngine() {
        return engine(TemplateMode.HTML);
    }

    @Bean("notificationTextEngine")
    public SpringTemplateEngine notificationTextEngine() {
        return engine(TemplateMode.TEXT);
    }

    @Bean
    public NotificationTemplates notificationTemplates(
            @Qualifier("notificationHtmlEngine") SpringTemplateEngine htmlEngine,
            @Qualifier("notificationTextEngine") SpringTemplateEngine textEngine) {
        return new NotificationTemplates(htmlEngine, textEngine);
    }

    private static SpringTemplateEngine engine(TemplateMode mode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/notification/");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode(mode);
        resolver.setSuffix(mode == TemplateMode.HTML ? ".html" : ".txt");
        resolver.setCacheable(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
