package com.bethanysbakery.api;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BakeryApiApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BakeryApiApplication.class);
        if (isCli(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }

    static boolean isCli(String[] args) {
        return Arrays.stream(args).anyMatch(arg ->
            arg.equals("--bootstrap-admin") || arg.startsWith("--bootstrap-admin=")
                || arg.equals("--pair-device") || arg.startsWith("--pair-device=")
        );
    }
}
