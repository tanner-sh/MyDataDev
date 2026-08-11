package com.example.dbadmin.desktop;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("desktop")
@EnableConfigurationProperties(DesktopRuntimeProperties.class)
public class DesktopConfiguration {
}
