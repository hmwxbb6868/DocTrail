package com.doctrail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Profiles;

@SpringBootApplication
public class DocTrailApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(DocTrailApplication.class, args);
        if (context.getEnvironment().acceptsProfiles(Profiles.of("stage2-auth-bootstrap"))) {
            SpringApplication.exit(context);
        }
    }
}
