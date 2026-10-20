package com.example.electronichome.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Switches on the {@code @Scheduled} jobs (the cleaning of the files of the acts that were not used). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
